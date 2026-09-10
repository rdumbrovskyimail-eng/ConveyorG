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
    val deepInvestigationLog: String = "",
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
                        statusMessage = "Запуск конвейера..."
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
            _uiState.update { it.copy(statusMessage = "Инициализация рабочей области...") }

            val orchestrator = AutonomousOrchestrator(
                context = context,
                geminiApiKeyProvider = geminiApiKeyProvider,
                gitHubTokenProvider = gitHubTokenProvider,
                httpClient = httpClient,
                shouldCloseHttpClient = false
            ).also { activeOrchestrator = it }

            val orchestratorObserverJob = launch { observeOrchestratorEvents(orchestrator) }

            taskResult = orchestrator.runAutonomousTask(
                owner = owner,
                repo = repo,
                branch = branch,
                userObjective = userObjective,
                maxSteps = maxSteps,
                maxRepairRounds = maxRepairRounds,
                existingWorkspaceManager = workspaceManager,
                existingGitHubEngine = gitHubEngine,
                existingToolBridge = compositeBridge,
                existingSwarmCoordinator = swarmCoordinator // Прямая передача координатора роя
            )

            orchestratorObserverJob.cancel()

            if (taskResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPhase = OrchestratorPhase.COMPLETED,
                        lastCommitSha = taskResult.commitSha,
                        deepInvestigationLog = taskResult.deepLogContent,
                        statusMessage = taskResult.finalMessage.ifBlank { "Миссия выполнена успешно!" }
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
                        deepInvestigationLog = taskResult.deepLogContent,
                        statusMessage = taskResult.finalMessage
                    )
                }
                _uiEvents.emit(ConveyorMissionUiEvent.MissionFailed(taskResult.finalMessage))
            }

        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                executeSagaRollback(workspaceManager)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentPhase = OrchestratorPhase.CANCELLED,
                        statusMessage = "Миссия отменена пользователем."
                    )
                }
                _uiEvents.emit(ConveyorMissionUiEvent.ToastNotification("Миссия отменена."))
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isRunning = false,
                    currentPhase = OrchestratorPhase.FAILED,
                    errorDetails = e.localizedMessage,
                    statusMessage = "Ошибка: ${e.localizedMessage}"
                )
            }
            _uiEvents.emit(ConveyorMissionUiEvent.MissionFailed(e.localizedMessage ?: "Сбой"))
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
                        val newCard = BuilderCardUiModel(
                            taskId = event.taskId,
                            role = event.role,
                            stageNumber = updatedCards.size + 1,
                            targetFile = event.targetFile,
                            status = BuilderStatus.PENDING
                        )
                        updatedCards.add(newCard)
                        current.copy(
                            builderCards = updatedCards,
                            barrierTotal = swarm.state.value.expectedBarrier,
                            barrierRemaining = swarm.state.value.remainingBarrier
                        )
                    }
                }
                is SwarmEvent.TaskStarted -> updateBuilderCardStatus(event.taskId, BuilderStatus.RUNNING)
                is SwarmEvent.TaskAwaitingDependency -> updateBuilderCardStatus(event.taskId, BuilderStatus.WAITING_DEPENDENCY)
                is SwarmEvent.ReportSubmitted -> {
                    updateBuilderCardStatus(event.taskId, event.status)
                    _uiState.update {
                        it.copy(barrierRemaining = event.remaining, statusMessage = "Отчет [${event.taskId}]: осталось ${event.remaining}")
                    }
                }
                is SwarmEvent.GreenLightIgnited -> {
                    _uiState.update {
                        it.copy(isGreenLightOn = true, statusMessage = "🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Все файлы зафиксированы.")
                    }
                    _uiEvents.emit(ConveyorMissionUiEvent.HapticTrigger(isStrong = true))
                    _uiEvents.emit(ConveyorMissionUiEvent.GreenLightIgnited(event.manifest.totalCompleted, event.manifest.totalDurationMs))
                }
                is SwarmEvent.CircuitBreakerTripped -> {
                    _uiState.update { it.copy(statusMessage = "Предохранитель: ${event.reason}") }
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
                is OrchestratorEvent.StepChanged -> {
                    _uiState.update { it.copy(currentStep = event.currentStep, maxSteps = event.maxSteps) }
                }
                is OrchestratorEvent.TokensUpdated -> calculateEstimatedCost()
                is OrchestratorEvent.ThinkingDelta -> {
                    _uiState.update { it.copy(liveOrchestratorThought = (it.liveOrchestratorThought + event.delta).takeLast(2000)) }
                }
                is OrchestratorEvent.DeepLogAppended -> {
                    _uiState.update { it.copy(deepInvestigationLog = it.deepInvestigationLog + event.chunk) }
                }
                is OrchestratorEvent.ToolExecuting -> _uiState.update { it.copy(activeToolName = event.name) }
                is OrchestratorEvent.ToolFinished -> _uiState.update { it.copy(activeToolName = null) }
                is OrchestratorEvent.CiStatusUpdated -> {
                    _uiState.update {
                        it.copy(ciStatus = event.status, ciConclusion = event.conclusion, ciRunHtmlUrl = event.runUrl)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun updateBuilderCardStatus(taskId: String, status: BuilderStatus) {
        _uiState.update { current ->
            val cards = current.builderCards.map { if (it.taskId == taskId) it.copy(status = status) else it }
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

        _uiState.update { it.copy(totalTokensBurned = totalTokens, estimatedCostUsd = totalCost) }
    }

    private fun executeSagaRollback(workspaceManager: LocalWorkspaceManager) {
        val sessionRoot = workspaceManager.workspaceRoot
        val backupDir = File(context.noBackupFilesDir, "workspaces/${workspaceManager.sessionId}/.baseline_orig")
        val baselinePaths = workspaceManager.getBaselinePaths()

        sessionRoot.walkTopDown().filter { it.isFile && !it.path.contains(".baseline") }.forEach { file ->
            val relPath = file.relativeTo(sessionRoot).path.replace('\\', '/')
            if (relPath !in baselinePaths) file.delete()
        }

        if (backupDir.exists()) {
            backupDir.walkTopDown().filter { it.isFile }.forEach { backupFile ->
                val relPath = backupFile.relativeTo(backupDir).path
                val targetFile = File(sessionRoot, relPath)
                targetFile.parentFile?.mkdirs()
                backupFile.copyTo(targetFile, overwrite = true)
            }
        }
    }

    fun cancelMission() {
        controllerScope.launch {
            missionMutex.withLock {
                if (!_uiState.value.isRunning) return@withLock
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
        if (shouldCloseHttpClient) httpClient.close()
    }
}

class CompositeOrchestratorToolBridge(
    workspaceManager: LocalWorkspaceManager,
    gitHubEngine: GitHubEngine,
    val swarmCoordinator: BuilderSwarmCoordinator
) : OrchestratorBridge {

    private val baseBridge = OrchestratorToolBridge(workspaceManager, gitHubEngine)

    override fun getToolDeclarations(): GeminiToolDto {
        val baseDeclarations = baseBridge.getToolDeclarations().functionDeclarations ?: emptyList()
        val swarmDeclarations = listOf(
            FunctionDeclarationDto(
                name = "swarm_dispatch_primary_builder",
                description = "Запускает строителя 3.5 Lite (Класс A).",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf(
                        "stage_number" to ParameterPropertyDto("INTEGER", "Номер этапа."),
                        "target_file" to ParameterPropertyDto("STRING", "Путь к файлу."),
                        "instruction" to ParameterPropertyDto("STRING", "Инструкция."),
                        "reference_code" to ParameterPropertyDto("STRING", "Эталонный код.")
                    ),
                    required = listOf("stage_number", "target_file", "instruction")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_dispatch_cross_builder",
                description = "Запускает сквозного строителя 3.5 Lite (Класс B), ожидающего предшественника.",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf(
                        "dependency_task_id" to ParameterPropertyDto("STRING", "ID родителя."),
                        "stage_number" to ParameterPropertyDto("INTEGER", "Номер этапа."),
                        "target_file" to ParameterPropertyDto("STRING", "Путь к файлу."),
                        "instruction" to ParameterPropertyDto("STRING", "Инструкция.")
                    ),
                    required = listOf("dependency_task_id", "stage_number", "target_file", "instruction")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_seal_barrier",
                description = "Запечатывает барьер синхронизации на N отчетов.",
                parameters = FunctionParametersSchemaDto(
                    properties = mapOf("expected_count" to ParameterPropertyDto("INTEGER", "Число задач.")),
                    required = listOf("expected_count")
                )
            ),
            FunctionDeclarationDto(
                name = "swarm_get_reports_manifest",
                description = "Возвращает сводный манифест после зажигания Зеленой лампочки.",
                parameters = FunctionParametersSchemaDto(properties = emptyMap())
            )
        )
        return GeminiToolDto(
            googleSearch = emptyMap(),
            functionDeclarations = baseDeclarations + swarmDeclarations
        )
    }

    override suspend fun dispatchToolCall(call: FunctionCallDto, thoughtSignature: String?): FunctionResponsePartDto {
        return try {
            when (call.name) {
                "swarm_dispatch_primary_builder" -> {
                    val stage = call.args["stage_number"]?.jsonPrimitive?.int ?: 1
                    val file = call.args["target_file"]?.jsonPrimitive?.content ?: "Unknown.kt"
                    val instruction = call.args["instruction"]?.jsonPrimitive?.content ?: ""
                    val refCode = call.args["reference_code"]?.jsonPrimitive?.contentOrNull
                    val taskId = swarmCoordinator.registerPrimaryBuilderA(stage, file, instruction, refCode)
                    FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("task_id", taskId) }, call.id))
                }
                "swarm_dispatch_cross_builder" -> {
                    val depId = call.args["dependency_task_id"]?.jsonPrimitive?.content ?: ""
                    val stage = call.args["stage_number"]?.jsonPrimitive?.int ?: 1
                    val file = call.args["target_file"]?.jsonPrimitive?.content ?: "Unknown.kt"
                    val instruction = call.args["instruction"]?.jsonPrimitive?.content ?: ""
                    val taskId = swarmCoordinator.registerCrossCuttingBuilderB(depId, stage, file, instruction)
                    FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("task_id", taskId) }, call.id))
                }
                "swarm_seal_barrier" -> {
                    val count = call.args["expected_count"]?.jsonPrimitive?.int ?: 20
                    swarmCoordinator.sealBarrier(count)
                    FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("status", "sealed") }, call.id))
                }
                "swarm_get_reports_manifest" -> {
                    val manifest = swarmCoordinator.awaitGreenLight(120_000L)
                    FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("total_completed", manifest.totalCompleted) }, call.id))
                }
                else -> baseBridge.dispatchToolCall(call, thoughtSignature)
            }
        } catch (e: Exception) {
            FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("error", e.localizedMessage ?: "Сбой") }, call.id))
        }
    }
}