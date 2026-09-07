package com.conveyorg.agent

import android.content.Context
import com.conveyorg.data.LocalWorkspaceManager
import com.conveyorg.network.*
import com.conveyorg.util.AppLogger
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.util.UUID

// ====================================================================
// 1. Модели Данных Единого Состояния Конвейера (120 Hz AMOLED UI)
// ====================================================================

data class BuilderCardUiModel(
    val taskId: String,
    val role: BuilderRole,
    val stageNumber: Int,
    val targetFile: String,
    val status: BuilderStatus,
    val summary: String = "",
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

data class ConveyorMissionUiState(
    val isRunning: Boolean = false,
    val sessionId: String = "",
    val repositoryName: String = "",
    val targetBranch: String = "",
    val missionObjective: String = "",
    val currentPhase: OrchestratorPhase = OrchestratorPhase.IDLE,
    val currentStep: Int = 0,
    val maxSteps: Int = 25,
    val repairRound: Int = 0,
    val maxRepairRounds: Int = 3,
    val liveOrchestratorThought: String = "",
    val activeToolName: String? = null,
    val isGreenLightOn: Boolean = false,
    val barrierTotal: Int = 0,
    val barrierRemaining: Int = 0,
    val builderCards: List<BuilderCardUiModel> = emptyList(),
    val lastCommitSha: String? = null,
    val ciStatus: String? = null,
    val ciConclusion: String? = null,
    val ciRunHtmlUrl: String? = null,
    val totalTokensBurned: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val statusMessage: String = "Конвейер ожидает задачу",
    val errorDetails: String? = null
)

sealed interface ConveyorMissionUiEvent {
    data class HapticTrigger(val isStrong: Boolean) : ConveyorMissionUiEvent
    data class GreenLightIgnited(val totalCompleted: Int, val durationMs: Long) : ConveyorMissionUiEvent
    data class MissionCompleted(val commitSha: String?, val totalCostUsd: Double) : ConveyorMissionUiEvent
    data class MissionFailed(val reason: String) : ConveyorMissionUiEvent
    data class ToastNotification(val message: String) : ConveyorMissionUiEvent
}

// ====================================================================
// 2. Главный Контроллер Конвейера: AutonomousConveyorController
// ====================================================================

class AutonomousConveyorController(
    private val context: Context,
    private val geminiApiKeyProvider: () -> String,
    private val gitHubTokenProvider: () -> String,
    private val httpClient: HttpClient = AutonomousOrchestrator.createDefaultHttpClient(),
    private val shouldCloseHttpClient: Boolean = true
) : Closeable {

    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val missionMutex = Mutex()
    private var missionJob: Job? = null

    private val _uiState = MutableStateFlow(ConveyorMissionUiState())
    val uiState: StateFlow<ConveyorMissionUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<ConveyorMissionUiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<ConveyorMissionUiEvent> = _uiEvents.asSharedFlow()

    private var activeWorkspaceManager: LocalWorkspaceManager? = null
    private var activeGitHubEngine: GitHubEngine? = null
    private var activeSwarmCoordinator: BuilderSwarmCoordinator? = null
    private var activeOrchestrator: AutonomousOrchestrator? = null

    companion object {
        private const val COST_PER_1M_INPUT_38 = 0.75
        private const val COST_PER_1M_OUTPUT_38 = 3.75
        private const val COST_PER_1M_INPUT_35 = 0.15
        private const val COST_PER_1M_OUTPUT_35 = 0.60
    }

    fun startMission(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        maxSteps: Int = 25,
        maxRepairRounds: Int = 3
    ) {
        controllerScope.launch {
            missionMutex.withLock {
                if (_uiState.value.isRunning) {
                    _uiEvents.emit(ConveyorMissionUiEvent.ToastNotification("Миссия уже выполняется!"))
                    return@withLock
                }

                val sessionId = UUID.randomUUID().toString()
                AppLogger.i(AppLogger.TAG_APP, "ConveyorController: Инициализация миссии [$sessionId] для $owner/$repo:$branch")

                _uiState.update {
                    ConveyorMissionUiState(
                        isRunning = true,
                        sessionId = sessionId,
                        repositoryName = "$owner/$repo",
                        targetBranch = branch,
                        missionObjective = userObjective,
                        currentPhase = OrchestratorPhase.INITIALIZING_WORKSPACE,
                        maxSteps = maxSteps,
                        maxRepairRounds = maxRepairRounds,
                        statusMessage = "Развертывание окружения UFS 4.0 и инициализация агентов..."
                    )
                }

                missionJob = launch {
                    executeMissionLifecycle(owner, repo, branch, userObjective, maxSteps, maxRepairRounds)
                }
            }
        }
    }

    private suspend fun executeMissionLifecycle(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        maxSteps: Int,
        maxRepairRounds: Int
    ) = withContext(Dispatchers.IO) {
        val sessionId = _uiState.value.sessionId

        val workspaceManager = LocalWorkspaceManager(sessionId, context).also { activeWorkspaceManager = it }
        val gitHubEngine = GitHubEngine(gitHubTokenProvider, httpClient, shouldCloseHttpClient = false).also { activeGitHubEngine = it }
        val swarmCoordinator = BuilderSwarmCoordinator(context, workspaceManager, geminiApiKeyProvider, httpClient, shouldCloseHttpClient = false).also { activeSwarmCoordinator = it }

        val compositeBridge = CompositeOrchestratorToolBridge(
            workspaceManager = workspaceManager,
            gitHubEngine = gitHubEngine,
            swarmCoordinator = swarmCoordinator
        )

        val swarmObserverJob = launch { observeSwarmEvents(swarmCoordinator) }

        var taskResult: AutonomousTaskResult? = null

        try {
            _uiState.update { it.copy(statusMessage = "Загрузка архива репозитория из GitHub...") }
            gitHubEngine.downloadAndUnpackZipball(owner, repo, branch, workspaceManager.workspaceRoot)

            _uiState.update { it.copy(statusMessage = "Фиксация SHA-256 контрольного снимка...") }
            val baselineFilesCount = workspaceManager.captureBaseline()

            val orchestrator = AutonomousOrchestrator(
                context = context,
                geminiApiKeyProvider = geminiApiKeyProvider,
                gitHubTokenProvider = gitHubTokenProvider,
                httpClient = httpClient,
                shouldCloseHttpClient = false
            ).also { activeOrchestrator = it }

            val orchestratorObserverJob = launch { observeOrchestratorEvents(orchestrator) }

            _uiState.update {
                it.copy(
                    currentPhase = OrchestratorPhase.REASONING_AND_PLANNING,
                    statusMessage = "Верховный Оркестратор 3.8 Flash исследует проект ($baselineFilesCount файлов)..."
                )
            }

            taskResult = orchestrator.runAutonomousTask(
                owner = owner,
                repo = repo,
                branch = branch,
                userObjective = userObjective,
                maxSteps = maxSteps,
                maxRepairRounds = maxRepairRounds,
                existingWorkspaceManager = workspaceManager,
                existingGitHubEngine = gitHubEngine,
                existingToolBridge = compositeBridge
            )

            orchestratorObserverJob.cancel()

            if (taskResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPhase = OrchestratorPhase.COMPLETED,
                        lastCommitSha = taskResult.commitSha,
                        statusMessage = "Миссия выполнена успешно! Код проверен и зафиксирован в GitHub."
                    )
                }
                _uiEvents.emit(ConveyorMissionUiEvent.HapticTrigger(isStrong = true))
                _uiEvents.emit(ConveyorMissionUiEvent.MissionCompleted(taskResult.commitSha, _uiState.value.estimatedCostUsd))
            } else {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPhase = OrchestratorPhase.FAILED,
                        errorDetails = taskResult.finalMessage,
                        statusMessage = "Миссия завершилась со сбоем: ${taskResult.finalMessage}"
                    )
                }
                _uiEvents.emit(ConveyorMissionUiEvent.MissionFailed(taskResult.finalMessage))
            }

        } catch (e: CancellationException) {
            AppLogger.w(AppLogger.TAG_APP, "ConveyorController: Миссия прервана пользователем.")
            withContext(NonCancellable) {
                executeSagaRollback(workspaceManager)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPhase = OrchestratorPhase.CANCELLED,
                        statusMessage = "Миссия отменена. Рабочая область возвращена к исходному снимку."
                    )
                }
                _uiEvents.emit(ConveyorMissionUiEvent.ToastNotification("Миссия отменена. Произведен откат изменений."))
            }
        } catch (e: Exception) {
            AppLogger.e(AppLogger.TAG_APP, "ConveyorController: Критический сбой миссии: ${e.message}", e)
            _uiState.update {
                it.copy(
                    isRunning = false,
                    currentPhase = OrchestratorPhase.FAILED,
                    errorDetails = e.localizedMessage,
                    statusMessage = "Фатальная ошибка: ${e.localizedMessage}"
                )
            }
            _uiEvents.emit(ConveyorMissionUiEvent.MissionFailed(e.localizedMessage ?: "Неизвестный сбой"))
        } finally {
            withContext(NonCancellable) {
                swarmObserverJob.cancel()
                cleanSessionResources()
            }
        }
    }

    private suspend fun observeSwarmEvents(swarm: BuilderSwarmCoordinator) {
        swarm.events.collect { event ->
            when (event) {
                is SwarmEvent.TaskRegistered -> {
                    _uiState.update { current ->
                        val updatedCards = current.builderCards.toMutableList()
                        val existingIdx = updatedCards.indexOfFirst { it.taskId == event.taskId }
                        val newCard = BuilderCardUiModel(
                            taskId = event.taskId,
                            role = event.role,
                            stageNumber = updatedCards.size + 1,
                            targetFile = event.targetFile,
                            status = BuilderStatus.PENDING
                        )
                        if (existingIdx != -1) {
                            updatedCards[existingIdx] = newCard
                        } else {
                            updatedCards.add(newCard)
                        }
                        current.copy(
                            builderCards = updatedCards,
                            barrierTotal = swarm.state.value.expectedBarrier,
                            barrierRemaining = swarm.state.value.remainingBarrier
                        )
                    }
                }
                is SwarmEvent.TaskStarted -> {
                    updateBuilderCardStatus(event.taskId, BuilderStatus.RUNNING)
                }
                is SwarmEvent.TaskAwaitingDependency -> {
                    updateBuilderCardStatus(event.taskId, BuilderStatus.WAITING_DEPENDENCY)
                }
                is SwarmEvent.ReportSubmitted -> {
                    updateBuilderCardStatus(event.taskId, event.status)
                    _uiState.update {
                        it.copy(
                            barrierRemaining = event.remaining,
                            statusMessage = "Получен отчет от ${event.taskId} (осталось: ${event.remaining})"
                        )
                    }
                    _uiEvents.emit(ConveyorMissionUiEvent.HapticTrigger(isStrong = false))
                }
                is SwarmEvent.GreenLightIgnited -> {
                    _uiState.update {
                        it.copy(
                            isGreenLightOn = true,
                            currentPhase = OrchestratorPhase.DUAL_LOOP_VERIFYING,
                            statusMessage = "🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Все 20 билдеров завершили работу. Переход к верификации."
                        )
                    }
                    _uiEvents.emit(ConveyorMissionUiEvent.HapticTrigger(isStrong = true))
                    _uiEvents.emit(ConveyorMissionUiEvent.GreenLightIgnited(event.manifest.totalCompleted, event.manifest.totalDurationMs))
                }
                is SwarmEvent.CircuitBreakerTripped -> {
                    _uiState.update { it.copy(statusMessage = "Предохранитель роя: ${event.reason}") }
                    _uiEvents.emit(ConveyorMissionUiEvent.ToastNotification("Предохранитель роя активирован!"))
                }
            }
        }
    }

    private suspend fun observeOrchestratorEvents(orchestrator: AutonomousOrchestrator) {
        orchestrator.events.collect { event ->
            when (event) {
                is OrchestratorEvent.PhaseChanged -> {
                    _uiState.update { it.copy(currentPhase = event.phase, statusMessage = event.description) }
                }
                is OrchestratorEvent.ThinkingDelta -> {
                    _uiState.update {
                        it.copy(
                            liveOrchestratorThought = (it.liveOrchestratorThought + event.delta).takeLast(2000)
                        )
                    }
                }
                is OrchestratorEvent.ToolExecuting -> {
                    _uiState.update { it.copy(activeToolName = event.name) }
                }
                is OrchestratorEvent.ToolFinished -> {
                    _uiState.update { it.copy(activeToolName = null) }
                }
                is OrchestratorEvent.CiStatusUpdated -> {
                    _uiState.update {
                        it.copy(
                            ciStatus = event.status,
                            ciConclusion = event.conclusion,
                            ciRunHtmlUrl = event.runUrl
                        )
                    }
                }
                is OrchestratorEvent.TaskFinished -> {
                    calculateEstimatedCost()
                }
                else -> Unit
            }
        }
    }

    private fun updateBuilderCardStatus(taskId: String, status: BuilderStatus) {
        _uiState.update { current ->
            val cards = current.builderCards.map { card ->
                if (card.taskId == taskId) card.copy(status = status) else card
            }
            current.copy(builderCards = cards)
        }
    }

    private fun calculateEstimatedCost() {
        val orchestratorState = activeOrchestrator?.state?.value ?: return
        val swarmManifestTokens = activeSwarmCoordinator?.getBurnedTokensSnapshot() ?: 0L

        val cost38Input = (orchestratorState.totalPromptTokens / 1_000_000.0) * COST_PER_1M_INPUT_38
        val cost38Output = ((orchestratorState.totalCandidateTokens + orchestratorState.totalThoughtsTokens) / 1_000_000.0) * COST_PER_1M_OUTPUT_38
        val cost35 = (swarmManifestTokens / 1_000_000.0) * ((COST_PER_1M_INPUT_35 + COST_PER_1M_OUTPUT_35) / 2.0)

        val totalTokens = orchestratorState.totalPromptTokens + orchestratorState.totalCandidateTokens + orchestratorState.totalThoughtsTokens + swarmManifestTokens
        val totalCost = cost38Input + cost38Output + cost35

        _uiState.update {
            it.copy(
                totalTokensBurned = totalTokens,
                estimatedCostUsd = totalCost
            )
        }
    }

    private fun executeSagaRollback(workspaceManager: LocalWorkspaceManager) {
        AppLogger.w(AppLogger.TAG_APP, "SagaRollback: Запуск компенсирующей транзакции (откат к .baseline_orig)...")
        val sessionRoot = workspaceManager.workspaceRoot
        val backupDir = File(context.noBackupFilesDir, "workspaces/${workspaceManager.sessionId}/.baseline_orig")
        val baselinePaths = workspaceManager.getBaselinePaths()

        sessionRoot.walkTopDown().filter { it.isFile && !it.path.contains(".baseline") }.forEach { file ->
            val relPath = file.relativeTo(sessionRoot).path.replace('\\', '/')
            if (relPath !in baselinePaths) {
                file.delete()
            }
        }

        if (backupDir.exists()) {
            backupDir.walkTopDown().filter { it.isFile }.forEach { backupFile ->
                val relPath = backupFile.relativeTo(backupDir).path
                val targetFile = File(sessionRoot, relPath)
                targetFile.parentFile?.mkdirs()
                backupFile.copyTo(targetFile, overwrite = true)
            }
            AppLogger.i(AppLogger.TAG_APP, "SagaRollback: Файлы успешно восстановлены из резервной копии.")
        }
    }

    fun cancelMission() {
        controllerScope.launch {
            missionMutex.withLock {
                if (!_uiState.value.isRunning) return@withLock
                AppLogger.w(AppLogger.TAG_APP, "ConveyorController: Поступила команда принудительной остановки миссии.")
                activeOrchestrator?.cancelTask()
                activeSwarmCoordinator?.close()
                missionJob?.cancel()
                missionJob = null
            }
        }
    }

    private fun cleanSessionResources() {
        activeSwarmCoordinator?.close()
        activeSwarmCoordinator = null
        activeOrchestrator?.close()
        activeOrchestrator = null
        activeGitHubEngine?.close()
        activeGitHubEngine = null
        activeWorkspaceManager = null
    }

    override fun close() {
        cancelMission()
        controllerScope.cancel()
        if (shouldCloseHttpClient) {
            httpClient.close()
        }
    }
}

// ====================================================================
// 7. Композитный Инструментальный Мост с Инъекцией Роя (Swarm Tools)
// ====================================================================

class CompositeOrchestratorToolBridge(
    private val workspaceManager: LocalWorkspaceManager,
    private val gitHubEngine: GitHubEngine,
    private val swarmCoordinator: BuilderSwarmCoordinator
) {
    private val baseBridge = OrchestratorToolBridge(workspaceManager, gitHubEngine)

    fun getToolDeclarations(): GeminiToolDto {
        val baseDeclarations = baseBridge.getToolDeclarations().functionDeclarations ?: emptyList()

        val swarmDeclarations = listOf(
            FunctionDeclarationDto(
                name = "swarm_dispatch_primary_builder",
                description = "Запускает автономного строителя 3.5 Lite (Класс A) для реализации 10% слоя архитектуры (этапы 1..10).",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf(
                        "stage_number" to ParameterPropertyDto(type = "INTEGER", description = "Номер этапа (1..10)."),
                        "target_file" to ParameterPropertyDto(type = "STRING", description = "Путь к файлу для реализации."),
                        "instruction" to ParameterPropertyDto(type = "STRING", description = "Точная инструкция по реализации функционала."),
                        "reference_code" to ParameterPropertyDto(type = "STRING", description = "Эталонный архитектурный код интерфейса или класса.")
                    ),
                    required = listOf("stage_number", "target_file", "instruction")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_dispatch_cross_builder",
                description = "Запускает сквозного строителя 3.5 Lite (Класс B), который строго ожидает завершения указанного предшественника (Закон A -> B).",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf(
                        "dependency_task_id" to ParameterPropertyDto(type = "STRING", description = "ID родительской задачи, завершения которой нужно дождаться."),
                        "stage_number" to ParameterPropertyDto(type = "INTEGER", description = "Номер текущего этапа."),
                        "target_file" to ParameterPropertyDto(type = "STRING", description = "Путь к файлу, в который вносится сквозная правка."),
                        "instruction" to ParameterPropertyDto(type = "STRING", description = "Инструкция по модификации существующего файла.")
                    ),
                    required = listOf("dependency_task_id", "stage_number", "target_file", "instruction")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_seal_barrier",
                description = "Запечатывает барьер синхронизации на точное количество ожидаемых отчетов N (максимум 20). Включает обратный отсчет до Зеленой лампочки.",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf(
                        "expected_count" to ParameterPropertyDto(type = "INTEGER", description = "Общее число запущенных задач (до 20).")
                    ),
                    required = listOf("expected_count")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_get_reports_manifest",
                description = "Возвращает сводный манифест всех отчетов билдеров после зажигания Зеленой лампочки для финального семантического аудита.",
                parameters = FunctionParametersSchemaDto(properties = emptyMap())
            )
        )

        return GeminiToolDto(functionDeclarations = baseDeclarations + swarmDeclarations)
    }

    suspend fun dispatchToolCall(
        call: FunctionCallDto,
        thoughtSignature: String? = null
    ): FunctionResponsePartDto {
        return when (call.name) {
            "swarm_dispatch_primary_builder" -> {
                val stage = call.args["stage_number"]?.jsonPrimitive?.int ?: 1
                val file = call.args["target_file"]?.jsonPrimitive?.content ?: "Unknown.kt"
                val instruction = call.args["instruction"]?.jsonPrimitive?.content ?: ""
                val refCode = call.args["reference_code"]?.jsonPrimitive?.contentOrNull

                val taskId = swarmCoordinator.registerPrimaryBuilderA(stage, file, instruction, refCode)
                createSuccessResponse(call.name, call.id, buildJsonObject {
                    put("status", "registered")
                    put("task_id", taskId)
                    put("role", "PRIMARY_A")
                    put("message", "Строитель A запущен в изолированном фоновом потоке.")
                })
            }
            "swarm_dispatch_cross_builder" -> {
                val depId = call.args["dependency_task_id"]?.jsonPrimitive?.content ?: ""
                val stage = call.args["stage_number"]?.jsonPrimitive?.int ?: 1
                val file = call.args["target_file"]?.jsonPrimitive?.content ?: "Unknown.kt"
                val instruction = call.args["instruction"]?.jsonPrimitive?.content ?: ""

                val taskId = swarmCoordinator.registerCrossCuttingBuilderB(depId, stage, file, instruction)
                createSuccessResponse(call.name, call.id, buildJsonObject {
                    put("status", "registered_waiting")
                    put("task_id", taskId)
                    put("role", "CROSS_CUTTING_B")
                    put("waiting_for", depId)
                    put("message", "Сквозной строитель B заблокирован до получения отчета от $depId.")
                })
            }
            "swarm_seal_barrier" -> {
                val count = call.args["expected_count"]?.jsonPrimitive?.int ?: 20
                val sealed = swarmCoordinator.sealBarrier(count)
                createSuccessResponse(call.name, call.id, buildJsonObject {
                    put("status", if (sealed) "sealed" else "error")
                    put("expected_total", count)
                    put("message", "Барьер запечатан. Обратный отсчет активирован.")
                })
            }
            "swarm_get_reports_manifest" -> {
                val manifest = swarmCoordinator.awaitGreenLight(timeoutMs = 120_000L)
                createSuccessResponse(call.name, call.id, buildJsonObject {
                    put("status", "green_light_manifest_ready")
                    put("total_completed", manifest.totalCompleted)
                    put("success_count", manifest.successCount)
                    put("failed_count", manifest.failedCount)
                    put("aborted_count", manifest.abortedCount)
                    putJsonArray("reports_summary") {
                        manifest.reports.forEach { r ->
                            addJsonObject {
                                put("task_id", r.taskId)
                                put("target_file", r.targetFile)
                                put("status", r.status)
                                put("summary", r.summary)
                            }
                        }
                    }
                })
            }
            else -> {
                baseBridge.dispatchToolCall(call, thoughtSignature)
            }
        }
    }

    private fun createSuccessResponse(name: String, callId: String?, output: JsonObject): FunctionResponsePartDto {
        return FunctionResponsePartDto(
            functionResponse = FunctionResponseDto(
                name = name,
                response = buildJsonObject { put("output", output) },
                id = callId
            )
        )
    }
}