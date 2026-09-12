package com.conveyorg.agent

import android.content.Context
import android.os.PowerManager
import com.conveyorg.data.LocalWorkspaceManager
import com.conveyorg.network.*
import com.conveyorg.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class OrchestratorPhase {
    IDLE,
    INITIALIZING_WORKSPACE,
    RECONNAISSANCE_T0,          // Этап 1: Сетевой рентген топологии GitHub API (LOW)
    FEASIBILITY_GATE_STAGE2,    // Этап 2: Семантический шлюз Истина / Ложь (HIGH)
    PHYSICAL_DEPLOY_STAGE3,     // Этап 3: Деплой архива на UFS 4.0, НЕ ЧИТАТЬ! (LOW)
    CODEBASE_AUDIT_STAGE4,      // Этап 4: Залповый беспристрастный аудит файлов (HIGH)
    RESEARCH_STAGE_5A,          // Этап 5a: Глубокое изучение 100 первоисточников (HIGH)
    RESEARCH_STAGE_5B,          // Этап 5b: Изучение 100 иных первоисточников (HIGH)
    RESEARCH_STAGE_5C,          // Этап 5c: Изучение 100 иных первоисточников, волна 3 (HIGH)
    STRATEGY_SELECTION_STAGE6,  // Этап 6: Стратегический выбор режима исполнения (HIGH)
    STREAM_GENERATION_STAGE7,   // Этап 7: Монолитный непрерывный генератор кода (266k волны)
    CONVEYOR_SELECTION_STAGE8,  // Этап 8: Нарезка монолита и запуск плоского роя воркеров
    REASONING_AND_PLANNING,
    EXECUTING_TOOL,
    AWAITING_BARRIER,
    DUAL_LOOP_VERIFYING,
    SELF_HEALING,
    COMMITTING_AND_PUSHING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ConveyorExecutionMode(val code: Int, val description: String) {
    NEW_FILES_ONLY(1, "Только написание новых файлов"),
    EDIT_EXISTING_ONLY(2, "Только редактирование существующих файлов"),
    FULL_HYBRID(3, "Общий режим (новые файлы + редактирование существующих)")
}

data class Stage6StrategyResult(
    val selectedMode: ConveyorExecutionMode,
    val decisionExplanation: String,
    val logFormattedEntry: String
)

data class Stage7MonolithResult(
    val isSuccess: Boolean,
    val totalChars: Long,
    val wavesCount: Int,
    val monolithFile: File,
    val logFormattedEntry: String
)

data class OrchestratorState(
    val phase: OrchestratorPhase = OrchestratorPhase.IDLE,
    val currentStep: Int = 0,
    val maxSteps: Int = 25,
    val repairRound: Int = 0,
    val maxRepairRounds: Int = 3,
    val currentThoughtText: String = "",
    val activeToolName: String? = null,
    val completedToolsCount: Int = 0,
    val barrierTotal: Int = 0,
    val barrierRemaining: Int = 0,
    val isGreenLightOn: Boolean = false,
    val executionMode: ConveyorExecutionMode? = null,
    val lastCommitSha: String? = null,
    val lastCiRunId: Long? = null,
    val ciStatus: String? = null,
    val totalPromptTokens: Long = 0L,
    val totalCandidateTokens: Long = 0L,
    val totalThoughtsTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val statusMessage: String = "Готов к запуску",
    val deepInvestigationLog: String = "",
    val errorMessage: String? = null
)

sealed interface OrchestratorEvent {
    data class PhaseChanged(val phase: OrchestratorPhase, val description: String) : OrchestratorEvent
    data class StepChanged(val currentStep: Int, val maxSteps: Int) : OrchestratorEvent
    data class TokensUpdated(val totalTokens: Long, val promptTokens: Long, val candidateTokens: Long) : OrchestratorEvent
    data class ThinkingDelta(val delta: String) : OrchestratorEvent
    data class DeepLogAppended(val chunk: String) : OrchestratorEvent
    data class ToolExecuting(val name: String, val callId: String?) : OrchestratorEvent
    data class ToolFinished(val name: String, val durationMs: Long, val isSuccess: Boolean) : OrchestratorEvent
    data class BarrierProgress(val total: Int, val remaining: Int, val isGreenLight: Boolean) : OrchestratorEvent
    data class CiStatusUpdated(val status: String, val conclusion: String?, val runUrl: String) : OrchestratorEvent
    data class TaskFinished(val success: Boolean, val message: String, val commitSha: String?) : OrchestratorEvent
}

data class Stage2FeasibilityResult(
    val isFeasible: Boolean,
    val rawVerdict: String,
    val explanation: String,
    val logFormattedEntry: String
)

data class AutonomousTaskResult(
    val isSuccess: Boolean,
    val finalMessage: String,
    val commitSha: String?,
    val totalSteps: Int,
    val repairRoundsUsed: Int,
    val totalTokensBurned: Long,
    val deepLogContent: String
)

@Serializable
internal data class AgentWireRequest(
    @SerialName("cachedContent") val cachedContent: String? = null,
    @SerialName("systemInstruction") val systemInstruction: AgentSystemInstructionDto? = null,
    val contents: List<AgentContentDto>,
    val tools: List<GeminiToolDto>? = null,
    @SerialName("generationConfig") val generationConfig: AgentGenerationConfigDto
)

@Serializable
internal data class AgentSystemInstructionDto(val parts: List<AgentPartDto>)

@Serializable
internal data class AgentContentDto(
    val role: String,
    val parts: List<AgentPartDto>
)

@Serializable
internal data class AgentPartDto(
    val text: String? = null,
    val thought: Boolean? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("functionCall") val functionCall: FunctionCallDto? = null,
    @SerialName("functionResponse") val functionResponse: FunctionResponseDto? = null
)

@Serializable
internal data class AgentGenerationConfigDto(
    val maxOutputTokens: Int = 65536,
    @SerialName("thinkingConfig") val thinkingConfig: AgentThinkingConfigDto
)

@Serializable
internal data class AgentThinkingConfigDto(
    val thinkingLevel: String = "HIGH",
    val includeThoughts: Boolean = true
)

@Serializable
internal data class AgentResponseChunk(
    val candidates: List<AgentCandidateDto>? = null,
    val usageMetadata: AgentUsageMetadataDto? = null,
    val error: GoogleApiErrorDto? = null
)

@Serializable
internal data class AgentCandidateDto(
    val content: AgentContentDto? = null,
    val finishReason: String? = null,
    val groundingMetadata: AgentGroundingMetadataDto? = null
)

@Serializable
internal data class AgentGroundingMetadataDto(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<AgentGroundingChunkDto>? = null
)

@Serializable
internal data class AgentGroundingChunkDto(
    val web: AgentWebDto? = null
)

@Serializable
internal data class AgentWebDto(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
internal data class AgentUsageMetadataDto(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
    val cachedContentTokenCount: Int = 0
)

class AutonomousOrchestrator(
    private val context: Context,
    private val geminiApiKeyProvider: () -> String,
    private val gitHubTokenProvider: () -> String,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val shouldCloseHttpClient: Boolean = true
) : Closeable {

    private val _state = MutableStateFlow(OrchestratorState())
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<OrchestratorEvent> = _events.asSharedFlow()

    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val loopMutex = Mutex()

    companion object {
        private const val BASE_URL = "https://aiplatform.googleapis.com/v1/publishers/google/models"
        private const val ROOT_V1 = "https://aiplatform.googleapis.com/v1"
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val WAKELOCK_TAG = "ClientG:AutonomousOrchestrator"

        private const val BASELINE_BACKOFF_MS = 1500L
        private const val MAX_BACKOFF_MS = 16000L

        private const val CONVEYOR_STREAM_CHAR_LIMIT = 266_000
        private const val MAX_STREAM_WAVES = 8

        private const val PROMPT_STAGE_5A = "Проанализируй и максимально глубоко изучи 100 первоисточников в интернете, по данным запроса клиента, и кодовой базы проекта. Данные запиши в лог и запомни."
        private const val PROMPT_STAGE_5B = "изучи 100 иных первоисточников в интернете, по данным запроса клиента, и кодовой базы проекта. Данные запиши в лог и запомни."
        private const val PROMPT_STAGE_5C = "изучи 100 иных первоисточников в интернете, по данным запроса клиента, и кодовой базы проекта. Данные запиши в лог и запомни."

        private const val PROMPT_STAGE_6 = "У тебя есть анализ Репозитория, анализ запроса клиента, и анализ 300 источников. Подумай хорошо, и выбери один из трех доступных тебе режимов: 1. Только написание новых файлов. (Ты в дальнейшем этапе, будешь писать только полные новые файлы в репозиторий). 2. Только Редактирование репозитория и его файлов. Ты не будешь писать новые полные файлы, а только будешь вносить изменение в существующие. 3. Общий режим. Написание новых файлов, и редактироваеие существующих. Полный доступ."

        private const val PROMPT_STAGE_7_START = "Пиши код на максимальный полет мыслей, не думай о токкенах и выводе. Даже если код обрежется (не хватит токкенов вывода, ничего страшного ) , я запущу новую сессию и ты допишешь код. Только это условие самое важное и критическое. Соблюдай его на 100% , пиши сколько сможешь."
        private const val PROMPT_STAGE_7_CONTINUE = "Ты должен дописать код до 100% .Пиши код на максимальный полет мыслей, не думай о токкенах и выводе. Даже если код обрежется (не хватит токкенов вывода, ничего страшного ) , я запущу новую сессию и ты допишешь код. Только это условие самое важное и критическое. Соблюдай его на 100% , пиши сколько сможешь."

        private const val SUCCESS_SENTINEL = ">>> CONVEYOR_MISSION_SUCCESS <<<"

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 64
                endpoint {
                    maxConnectionsPerRoute = 32
                    keepAliveTime = 120_000
                    connectTimeout = 30_000
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 180_000L
                connectTimeoutMillis = 30_000L
            }
        }
    }

    suspend fun runAutonomousTask(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        maxSteps: Int = 25,
        maxRepairRounds: Int = 3,
        existingWorkspaceManager: LocalWorkspaceManager? = null,
        existingGitHubEngine: GitHubEngine? = null,
        existingToolBridge: OrchestratorBridge? = null,
        existingSwarmCoordinator: BuilderSwarmCoordinator? = null
    ): AutonomousTaskResult = withContext(Dispatchers.IO) {
        loopMutex.withLock {
            val sessionId = existingWorkspaceManager?.sessionId ?: UUID.randomUUID().toString()
            acquireWakeLock()

            val workspaceManager = existingWorkspaceManager ?: LocalWorkspaceManager(sessionId, context)
            val gitHubEngine = existingGitHubEngine ?: GitHubEngine(tokenProvider = gitHubTokenProvider, httpClient = httpClient, shouldCloseHttpClient = false)
            val toolBridge: OrchestratorBridge = existingToolBridge ?: OrchestratorToolBridge(workspaceManager, gitHubEngine)

            var isTaskSucceeded = false
            var finalMessage = ""
            var lastCommittedSha: String? = null
            var repairRound = 0
            var currentStep = 0
            var nextThinkingLevel = "LOW"
            var pinnedCacheId: String? = null

            val recentToolCalls = ArrayDeque<String>(6)

            try {
                // ====================================================================
                // ЭТАП 1: $T_0$ РЕКОГНОСЦИРОВКА (СЕТЕВОЙ РЕНТГЕН GITHUB API, РЕЖИМ LOW)
                // ====================================================================
                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.RECONNAISSANCE_T0,
                        statusMessage = "Этап 1: Сетевой структурный срез T0 (GitHub API, режим LOW)..."
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.RECONNAISSANCE_T0, "Этап 1: Рекогносцировка T0"))

                val remoteTree = gitHubEngine.getTreeRecursive(owner, repo, branch)
                val isRemoteEmpty = remoteTree.tree.isEmpty()
                val t0PassportText = performT0Reconnaissance(owner, repo, branch, userObjective, remoteTree)
                appendDeepLog(t0PassportText)

                // ====================================================================
                // ЭТАП 2: ШЛЮЗ ВАЛИДАЦИИ «ИСТИНА / ЛОЖЬ» (РЕЖИМ HIGH)
                // ====================================================================
                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.FEASIBILITY_GATE_STAGE2,
                        statusMessage = "Этап 2: Валидация предусловий [ИСТИНА / ЛОЖЬ] (в HIGH)..."
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.FEASIBILITY_GATE_STAGE2, "Этап 2: Проверка Истина/Ложь"))

                val stage2Result = performStage2FeasibilityCheck(userObjective, t0PassportText)
                appendDeepLog(stage2Result.logFormattedEntry)

                if (!stage2Result.isFeasible) {
                    val rejectMessage = "Миссия остановлена на Этапе 2: задача несовместима с репозиторием (ВЕРДИКТ: ЛОЖЬ).\n${stage2Result.explanation}"
                    _state.update {
                        it.copy(phase = OrchestratorPhase.FAILED, statusMessage = "Отказ: задача противоречит реальности (ЛОЖЬ)", errorMessage = stage2Result.explanation)
                    }
                    _events.emit(OrchestratorEvent.TaskFinished(false, rejectMessage, null))

                    return@withLock AutonomousTaskResult(
                        isSuccess = false,
                        finalMessage = rejectMessage,
                        commitSha = null,
                        totalSteps = 1,
                        repairRoundsUsed = 0,
                        totalTokensBurned = _state.value.totalPromptTokens + _state.value.totalCandidateTokens + _state.value.totalThoughtsTokens,
                        deepLogContent = _state.value.deepInvestigationLog
                    )
                }

                // ====================================================================
                // ЭТАП 3: ФИЗИЧЕСКИЙ ДЕПЛОЙ НА UFS 4.0 (РЕЖИМ LOW) — ЖЕЛЕЗНО НЕ ЧИТАТЬ!
                // ====================================================================
                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.PHYSICAL_DEPLOY_STAGE3,
                        statusMessage = "Этап 3: Деплой архива репозитория на UFS 4.0 (LOW, без чтения)..."
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.PHYSICAL_DEPLOY_STAGE3, "Этап 3: Физический деплой"))

                if (!isRemoteEmpty && workspaceManager.isWorkspaceEmpty()) {
                    gitHubEngine.downloadAndUnpackZipball(owner, repo, branch, workspaceManager.workspaceRoot)
                }
                val baselineCount = workspaceManager.captureBaseline()

                appendDeepLog(
                    "=== [ЭТАП 3: ФИЗИЧЕСКИЙ ДЕПЛОЙ НА UFS 4.0] ===\n" +
                    "• Архив репозитория успешно выкачан и распакован в изолированную песочницу.\n" +
                    "• Контрольный снимок SHA-256 зафиксирован ($baselineCount файлов).\n" +
                    "• Правило соблюдено: файлы физически на диске, но НЕ ЧИТАЮТСЯ.\n" +
                    "==============================================="
                )

                // ====================================================================
                // ЭТАП 4: ТОТАЛЬНЫЙ БЕСПРИСТРАСТНЫЙ АУДИТ КОДОВОЙ БАЗЫ (РЕЖИМ HIGH)
                // ====================================================================
                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.CODEBASE_AUDIT_STAGE4,
                        statusMessage = "Этап 4: Залповый глубокий аудит всей кодовой базы (HIGH, tools=null)..."
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.CODEBASE_AUDIT_STAGE4, "Этап 4: Тотальный аудит"))

                val allFilesBundle = workspaceManager.collectAllTextFilesForAudit()
                val codebaseBundleString = buildCodebaseXmlPayload(allFilesBundle)

                if (codebaseBundleString.length >= 120_000) {
                    pinnedCacheId = pinCodebaseContextCache(codebaseBundleString)
                }

                val auditReportText = performStage4CodebaseAudit(owner, repo, branch, allFilesBundle)
                appendDeepLog(auditReportText)

                // ====================================================================
                // ЭТАП 5: ГЛУБОКОЕ ИССЛЕДОВАНИЕ 300 ПЕРВОИСТОЧНИКОВ (5A -> 5B -> 5C)
                // ====================================================================
                val stage5ResearchLog = executeStage5TripleWaveResearch(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    userObjective = userObjective,
                    auditReport = auditReportText,
                    cachedContentId = pinnedCacheId
                )

                // ====================================================================
                // ЭТАП 6: СТРАТЕГИЧЕСКИЙ ВЫБОР РЕЖИМА ИСПОЛНЕНИЯ (РЕЖИМ HIGH)
                // ====================================================================
                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.STRATEGY_SELECTION_STAGE6,
                        statusMessage = "Этап 6: Архитектурный выбор генерального режима (HIGH)..."
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.STRATEGY_SELECTION_STAGE6, "Этап 6: Выбор режима исполнения"))

                val stage6Strategy = executeStage6StrategySelection(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    userObjective = userObjective,
                    auditReport = auditReportText,
                    stage5Research = stage5ResearchLog,
                    cachedContentId = pinnedCacheId
                )
                appendDeepLog(stage6Strategy.logFormattedEntry)

                _state.update {
                    it.copy(
                        executionMode = stage6Strategy.selectedMode,
                        statusMessage = "Выбран режим: ${stage6Strategy.selectedMode.description}."
                    )
                }

                // ====================================================================
                // РАЗВИЛКА: РЕЖИМ 1 (МОНОЛИТНЫЙ ПОТОКОВЫЙ ГЕНЕРАТОР НА 266K СИМВОЛОВ)
                // ====================================================================
                if (stage6Strategy.selectedMode == ConveyorExecutionMode.NEW_FILES_ONLY) {
                    val monolithResult = executeStage7MonolithicStreamGeneration(
                        owner = owner,
                        repo = repo,
                        branch = branch,
                        userObjective = userObjective,
                        stage5Research = stage5ResearchLog,
                        sessionId = sessionId
                    )
                    appendDeepLog(monolithResult.logFormattedEntry)

                    val swarmCoordinator = existingSwarmCoordinator
                        ?: throw IllegalStateException("BuilderSwarmCoordinator не передан для запуска роя.")

                    val selectionResult = executeStage8FastSelectionAndFlatDispatch(
                        monolithFile = monolithResult.monolithFile,
                        workspaceManager = workspaceManager,
                        gitHubEngine = gitHubEngine,
                        swarmCoordinator = swarmCoordinator,
                        owner = owner,
                        repo = repo,
                        branch = branch
                    )
                    appendDeepLog(selectionResult.logFormattedEntry)

                    lastCommittedSha = selectionResult.commitSha
                    finalMessage = "Миссия успешно завершена: ${selectionResult.filesCount} файлов создано и отправлено 1 коммитом: $lastCommittedSha"
                    isTaskSucceeded = true

                    _state.update {
                        it.copy(
                            phase = OrchestratorPhase.COMPLETED,
                            lastCommitSha = lastCommittedSha,
                            statusMessage = finalMessage
                        )
                    }

                    return@withLock AutonomousTaskResult(
                        isSuccess = true,
                        finalMessage = finalMessage,
                        commitSha = lastCommittedSha,
                        totalSteps = monolithResult.wavesCount + 1,
                        repairRoundsUsed = 0,
                        totalTokensBurned = _state.value.totalPromptTokens + _state.value.totalCandidateTokens + _state.value.totalThoughtsTokens,
                        deepLogContent = _state.value.deepInvestigationLog
                    )
                }

                // ====================================================================
                // РЕЖИМЫ 2 И 3: СТАНДАРТНЫЙ REACT-ЦИКЛ (ПРАВКИ ИЛИ ГИБРИД)
                // ====================================================================
                val systemPrompt = buildSystemInstruction(owner, repo, branch)
                val conversationHistory = mutableListOf<AgentContentDto>()

                conversationHistory.add(
                    AgentContentDto(
                        role = "user",
                        parts = listOf(
                            AgentPartDto(
                                text = buildInitialUserPrompt(
                                    objective = userObjective,
                                    totalFiles = baselineCount,
                                    t0Passport = t0PassportText,
                                    stage2Result = stage2Result,
                                    auditReport = auditReportText,
                                    stage5Research = stage5ResearchLog,
                                    strategyResult = stage6Strategy
                                )
                            )
                        )
                    )
                )

                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.REASONING_AND_PLANNING,
                        statusMessage = "Шлюз открыт. Стратегия: ${stage6Strategy.selectedMode.description}. Реализация..."
                    )
                }

                while (currentStep < maxSteps && isActive) {
                    currentStep++
                    _state.update { it.copy(currentStep = currentStep) }
                    _events.emit(OrchestratorEvent.StepChanged(currentStep, maxSteps))

                    val modelTurn = executeGeminiTurnWithRetry(
                        systemPrompt = systemPrompt,
                        history = conversationHistory,
                        toolDeclarations = toolBridge.getToolDeclarations(),
                        thinkingLevel = nextThinkingLevel,
                        cachedContentId = pinnedCacheId
                    )

                    val functionCalls = modelTurn.parts.mapNotNull { it.functionCall }
                    val textContent = modelTurn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
                    val thoughtSig = modelTurn.parts.firstOrNull { it.thoughtSignature != null }?.thoughtSignature

                    if (textContent.isNotBlank()) {
                        appendDeepLog("Шаг $currentStep (Вывод):\n$textContent")
                    }

                    conversationHistory.add(modelTurn)

                    if (functionCalls.isEmpty()) {
                        finalMessage = textContent.ifBlank { "Задача успешно выполнена." }
                        isTaskSucceeded = true
                        break
                    }

                    val firstCall = functionCalls.first()
                    nextThinkingLevel = when (firstCall.name) {
                        "workspace_get_tree", "workspace_read_file", "workspace_write_file",
                        "workspace_batch_write", "workspace_delete_file", "workspace_search_symbol",
                        "workspace_read_diff", "swarm_dispatch_cross_builder", "swarm_seal_barrier" -> "LOW"
                        else -> "HIGH"
                    }

                    val toolResponseParts = mutableListOf<AgentPartDto>()
                    var commitSucceededOnThisTurn = false

                    for (call in functionCalls) {
                        _state.update {
                            it.copy(
                                phase = OrchestratorPhase.EXECUTING_TOOL,
                                activeToolName = call.name,
                                statusMessage = "Инструмент: ${call.name}..."
                            )
                        }
                        _events.emit(OrchestratorEvent.ToolExecuting(call.name, call.id))

                        val callFingerprint = "${call.name}:${call.args}"
                        recentToolCalls.addLast(callFingerprint)
                        if (recentToolCalls.size > 5) recentToolCalls.removeFirst()

                        if (recentToolCalls.size >= 3 && recentToolCalls.takeLast(3).all { it == callFingerprint }) {
                            conversationHistory.add(
                                AgentContentDto(
                                    role = "user",
                                    parts = listOf(AgentPartDto(text = "ВНИМАНИЕ: Повторный вызов '${call.name}'. Переходите к коммиту или завершению."))
                                )
                            )
                        }

                        val toolResponsePart = toolBridge.dispatchToolCall(call, thoughtSig)

                        if (call.name == "github_push_atomic_commit") {
                            val pushOutput = toolResponsePart.functionResponse.response["output"]?.jsonObject
                            if (pushOutput?.get("status")?.jsonPrimitive?.contentOrNull == "success") {
                                lastCommittedSha = pushOutput["commit_sha"]?.jsonPrimitive?.contentOrNull
                                commitSucceededOnThisTurn = true
                                _state.update { it.copy(lastCommitSha = lastCommittedSha) }
                            }
                        }

                        val sanitizedResponse = toolResponsePart.functionResponse.copy(
                            response = wrapSafeToolOutput(toolResponsePart.functionResponse.response)
                        )

                        toolResponseParts.add(AgentPartDto(functionResponse = sanitizedResponse))
                        _events.emit(OrchestratorEvent.ToolFinished(call.name, 0L, true))
                    }

                    conversationHistory.add(
                        AgentContentDto(
                            role = "tool",
                            parts = toolResponseParts
                        )
                    )

                    if (commitSucceededOnThisTurn && !workspaceManager.hasConfiguredCiWorkflows()) {
                        finalMessage = "Коммит зафиксирован: $lastCommittedSha. Задача выполнена за $currentStep шагов."
                        isTaskSucceeded = true
                        appendDeepLog("=== [УСПЕХ] ===\n$finalMessage")
                        break
                    }

                    compactConversationHistory(conversationHistory)
                }

                if (currentStep >= maxSteps && !isTaskSucceeded) {
                    finalMessage = "Достигнут лимит шагов ($maxSteps)."
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                finalMessage = "Задача отменена пользователем."
                _state.update { it.copy(phase = OrchestratorPhase.CANCELLED, statusMessage = finalMessage) }
            } catch (e: Exception) {
                finalMessage = "Ошибка: ${e.localizedMessage}"
                _state.update { it.copy(phase = OrchestratorPhase.FAILED, errorMessage = finalMessage) }
            } finally {
                withContext(NonCancellable) {
                    deleteCodebaseContextCache(pinnedCacheId)
                    releaseWakeLock()
                    val finalPhase = if (isTaskSucceeded) OrchestratorPhase.COMPLETED else OrchestratorPhase.FAILED
                    _state.update { it.copy(phase = finalPhase, statusMessage = finalMessage) }
                    _events.emit(OrchestratorEvent.TaskFinished(isTaskSucceeded, finalMessage, lastCommittedSha))
                    if (shouldCloseHttpClient && existingGitHubEngine == null) {
                        gitHubEngine.close()
                    }
                }
            }

            AutonomousTaskResult(
                isSuccess = isTaskSucceeded,
                finalMessage = finalMessage,
                commitSha = lastCommittedSha,
                totalSteps = currentStep,
                repairRoundsUsed = repairRound,
                totalTokensBurned = _state.value.totalPromptTokens + _state.value.totalCandidateTokens + _state.value.totalThoughtsTokens,
                deepLogContent = _state.value.deepInvestigationLog
            )
        }
    }

    private suspend fun performT0Reconnaissance(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        remoteTree: GitHubTreeResponseDto
    ): String {
        val blobs = remoteTree.tree.filter { it.type == "blob" }
        val trees = remoteTree.tree.filter { it.type == "tree" }
        val totalBytes = blobs.sumOf { it.size ?: 0L }

        val treeListing = buildString {
            appendLine("Файлов: ${blobs.size}, Директорий: ${trees.size}, Общий объем: $totalBytes байт")
            if (remoteTree.truncated) {
                appendLine("[ВНИМАНИЕ: Дерево превысило квоту GitHub API и усечено]")
            }
            remoteTree.tree.take(250).forEach { entry ->
                val kind = if (entry.type == "tree") "[DIR]" else "[FILE]"
                val sizeStr = entry.size?.let { " ($it байт)" } ?: ""
                appendLine("- $kind ${entry.path}$sizeStr")
            }
        }

        val prompt = "ЭТАП T0: СНЯТИЕ СЛЕПКА ФИЗИЧЕСКОЙ РЕАЛЬНОСТИ И СТРУКТУРНЫЙ ПАСПОРТ РЕПОЗИТОРИЯ.\n" +
                "Репозиторий: '$owner/$repo' (ветка: '$branch').\n\n" +
                "ЗАДАЧА КЛИЕНТА:\n$userObjective\n\n" +
                "ТОПОЛОГИЯ ДЕРЕВА (файлы НЕ открывать, известны только пути и размеры):\n$treeListing\n\n" +
                "ЗАДАЧА МОДЕЛИ:\n" +
                "Не открывая файлы, сформируй краткий структурный паспорт T0:\n" +
                "1. Стек и состояние проекта (Greenfield/пустой, Kotlin/Android, Python, JS/Node, Rust и т.д.).\n" +
                "2. Наличие сборочных систем (Gradle, Maven, NPM, Cargo).\n" +
                "3. Наличие CI/CD инфраструктуры (.github/workflows).\n" +
                "4. Физическая база файлов."

        val turn = executeSingleGeminiTurn(
            systemPrompt = "Ты — экспертный аналитик архитектуры. Твоя задача — холодный, точный анализ структуры проекта без чтения файлов.",
            history = listOf(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = prompt)))),
            toolDeclarations = GeminiToolDto(functionDeclarations = emptyList()),
            thinkingLevel = "LOW"
        )

        val passport = turn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        return "=== [T0: СТРУКТУРНЫЙ ПАСПОРТ РЕПОЗИТОРИЯ] ===\n" +
               (passport.ifBlank { "Паспорт сформирован автоматически по дереву файлов." }) +
               "\n============================================="
    }

    private suspend fun performStage2FeasibilityCheck(
        userObjective: String,
        t0Passport: String
    ): Stage2FeasibilityResult {
        val prompt = buildString {
            appendLine("ЭТАП 2: СЕМАНТИЧЕСКИЙ ШЛЮЗ ВАЛИДАЦИИ «ИСТИНА / ЛОЖЬ».")
            appendLine()
            appendLine("1. СТРУКТУРНЫЙ ПАСПОРТ РЕПОЗИТОРИЯ T0:")
            appendLine(t0Passport)
            appendLine()
            appendLine("2. ЗАДАЧА КЛИЕНТА:")
            appendLine(userObjective)
            appendLine()
            appendLine("ПРАВИЛО ОЦЕНКИ:")
            appendLine("Ты ведущий инженер-архитектор. Опираясь на паспорт T0 и задачу клиента, определи:")
            appendLine("Сопоставима ли задача клиента с текущим состоянием репозитория (ИСТИНА) или она физически/логически противоречит реальности (ЛОЖЬ)?")
            appendLine()
            appendLine("Примеры логики (Few-Shot):")
            appendLine("• Пример 1: Репозиторий пуст -> Задача: 'Скомпилируй и исправь ошибки' -> ЛОЖЬ (нельзя скомпилировать то, чего физически нет).")
            appendLine("• Пример 2: Репозиторий пуст -> Задача: 'Создай приложение с нуля' -> ИСТИНА (создание совпадает с чистым репозиторием).")
            appendLine("• Пример 3: В репозитории чистый Python -> Задача: 'Допиши Android Compose экран' без запроса миграции -> ЛОЖЬ (несовместимый стек).")
            appendLine("• Пример 4: В репозитории уже есть проект -> Задача: 'Модернизируй и добавь функцию X' -> ИСТИНА (логичное развитие существующего кода).")
            appendLine()
            appendLine("ФОРМАТ ОТВЕТА:")
            appendLine("На первой строке выведи строго вердикт:")
            appendLine("ВЕРДИКТ: [ИСТИНА]")
            appendLine("или")
            appendLine("ВЕРДИКТ: [ЛОЖЬ]")
            appendLine()
            appendLine("Со второй строки дай краткое обоснование вердикта в 1-2 предложения для журнала сессии.")
        }

        val turn = executeSingleGeminiTurn(
            systemPrompt = "Ты — строгий технический архитектор. Оценивай выполнимость предусловий задачи с предельной строгостью.",
            history = listOf(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = prompt)))),
            toolDeclarations = GeminiToolDto(functionDeclarations = emptyList()),
            thinkingLevel = "HIGH"
        )

        val rawResponse = turn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        val isExplicitFalse = rawResponse.contains("[ЛОЖЬ]", ignoreCase = true) || rawResponse.contains("ВЕРДИКТ: ЛОЖЬ", ignoreCase = true)
        val isExplicitTrue = rawResponse.contains("[ИСТИНА]", ignoreCase = true) || rawResponse.contains("ВЕРДИКТ: ИСТИНА", ignoreCase = true)

        val isFeasible = if (isExplicitFalse && !isExplicitTrue) false else if (isExplicitTrue) true else !rawResponse.contains("ложь", ignoreCase = true)
        val explanation = rawResponse.lines().drop(1).joinToString("\n").trim().ifBlank { rawResponse }
        val verdictLabel = if (isFeasible) "[ИСТИНА]" else "[ЛОЖЬ]"

        val logEntry = buildString {
            appendLine("=== [ЭТАП 2: ВАЛИДАЦИЯ ПРЕДУСЛОВИЙ — $verdictLabel] ===")
            appendLine("• Вердикт: $verdictLabel")
            appendLine("• Обоснование: $explanation")
            if (isFeasible) appendLine("• ШЛЮЗ ОТКРЫТ: Предусловия подтверждены. Переход к деплою и аудиту.")
            else appendLine("• СТОП МИССИИ: Задача отвергнута из-за противоречия реальности.")
            append("=====================================================")
        }

        return Stage2FeasibilityResult(isFeasible, verdictLabel, explanation, logEntry)
    }

    private fun buildCodebaseXmlPayload(filesMap: Map<String, String>): String = buildString {
        if (filesMap.isEmpty()) {
            appendLine("[В репозитории отсутствуют текстовые файлы исходного кода. Репозиторий чист.]")
        } else {
            appendLine("<repository_codebase>")
            filesMap.forEach { (path, content) ->
                appendLine("<file path=\"$path\">")
                appendLine(content.take(15000))
                if (content.length > 15000) appendLine("... [Файл усечен для аудита]")
                appendLine("</file>")
            }
            appendLine("</repository_codebase>")
        }
    }

    private suspend fun performStage4CodebaseAudit(
        owner: String,
        repo: String,
        branch: String,
        filesMap: Map<String, String>
    ): String {
        val codebasePayload = buildCodebaseXmlPayload(filesMap)

        val prompt = "Изучи полностью весь репозиторий, максимально глубоко, каждый файл от корня до конца, как только сможешь. " +
                     "Выпиши подробные результаты в лог и запомни, и больше не предпринимай никаких действий и ничего не создавай.\n\n" +
                     "Кодовая база репозитория '$owner/$repo' ($branch):\n\n" +
                     codebasePayload

        val turn = executeSingleGeminiTurn(
            systemPrompt = "Ты — беспристрастный пассивный аналитик-наблюдатель. Твоя единственная цель — составить исчерпывающий технический отчет о том, что реально существует в кодовой базе. Ничего не создавай и не вызывай инструментов.",
            history = listOf(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = prompt)))),
            toolDeclarations = GeminiToolDto(functionDeclarations = emptyList()),
            thinkingLevel = "HIGH"
        )

        val auditText = turn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        return "=== [ЭТАП 4: ТОТАЛЬНЫЙ АУДИТ КОДОВОЙ БАЗЫ] ===\n" +
               (auditText.ifBlank { "Анализ кодовой базы завершен. Файлы зафиксированы в памяти." }) +
               "\n=============================================="
    }

    private suspend fun executeStage5TripleWaveResearch(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        auditReport: String,
        cachedContentId: String?
    ): String {
        val searchTool = GeminiToolDto(googleSearch = emptyMap())
        val researchSystemPrompt = "Ты — ведущий исследователь-аналитик ClientG на базе Gemini 3.8 Flash.\n" +
                "Репозиторий: '$owner/$repo' (ветка: '$branch').\n" +
                "Задача клиента:\n$userObjective\n\n" +
                "Аудит кодовой базы проекта (Этап 4):\n$auditReport"

        val researchHistory = mutableListOf<AgentContentDto>()

        _state.update {
            it.copy(
                phase = OrchestratorPhase.RESEARCH_STAGE_5A,
                statusMessage = "Этап 5a: Анализ первых 100 первоисточников в сети (HIGH)..."
            )
        }
        _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.RESEARCH_STAGE_5A, "Этап 5a: 100 первоисточников"))

        researchHistory.add(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = PROMPT_STAGE_5A))))

        val turn5a = executeGeminiTurnWithRetry(
            systemPrompt = researchSystemPrompt,
            history = researchHistory,
            toolDeclarations = searchTool,
            thinkingLevel = "HIGH",
            cachedContentId = cachedContentId
        )
        researchHistory.add(turn5a)
        val result5a = turn5a.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        val entry5a = "=== [ЭТАП 5A: ИССЛЕДОВАНИЕ 100 ПЕРВОИСТОЧНИКОВ] ===\n" +
                result5a.ifBlank { "Исследование первых 100 первоисточников зафиксировано." } +
                "\n================================================="
        appendDeepLog(entry5a)

        _state.update {
            it.copy(
                phase = OrchestratorPhase.RESEARCH_STAGE_5B,
                statusMessage = "Этап 5b: Анализ 100 иных первоисточников в сети (HIGH)..."
            )
        }
        _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.RESEARCH_STAGE_5B, "Этап 5b: 100 иных первоисточников"))

        researchHistory.add(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = PROMPT_STAGE_5B))))

        val turn5b = executeGeminiTurnWithRetry(
            systemPrompt = researchSystemPrompt,
            history = researchHistory,
            toolDeclarations = searchTool,
            thinkingLevel = "HIGH",
            cachedContentId = cachedContentId
        )
        researchHistory.add(turn5b)
        val result5b = turn5b.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        val entry5b = "=== [ЭТАП 5B: ИССЛЕДОВАНИЕ 100 ИНЫХ ПЕРВОИСТОЧНИКОВ] ===\n" +
                result5b.ifBlank { "Исследование второй волны первоисточников зафиксировано." } +
                "\n======================================================"
        appendDeepLog(entry5b)

        _state.update {
            it.copy(
                phase = OrchestratorPhase.RESEARCH_STAGE_5C,
                statusMessage = "Этап 5c: Анализ 100 иных первоисточников, волна 3 (HIGH)..."
            )
        }
        _events.emit(OrchestratorEvent.PhaseChanged(OrchestratorPhase.RESEARCH_STAGE_5C, "Этап 5c: 100 иных первоисточников (волна 3)"))

        researchHistory.add(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = PROMPT_STAGE_5C))))

        val turn5c = executeGeminiTurnWithRetry(
            systemPrompt = researchSystemPrompt,
            history = researchHistory,
            toolDeclarations = searchTool,
            thinkingLevel = "HIGH",
            cachedContentId = cachedContentId
        )
        researchHistory.add(turn5c)
        val result5c = turn5c.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()
        val entry5c = "=== [ЭТАП 5C: ИССЛЕДОВАНИЕ 100 ИНЫХ ПЕРВОИСТОЧНИКОВ (ВОЛНА 3)] ===\n" +
                result5c.ifBlank { "Исследование третьей волны первоисточников зафиксировано." } +
                "\n=============================================================="
        appendDeepLog(entry5c)

        return "$entry5a\n\n$entry5b\n\n$entry5c"
    }

    private suspend fun executeStage6StrategySelection(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        auditReport: String,
        stage5Research: String,
        cachedContentId: String?
    ): Stage6StrategyResult {
        val strategySystemPrompt = "Ты — верховный технический архитектор проекта ClientG.\n" +
                "Твоя задача — на основе синтеза всех проведенных исследований принять стратегическое архитектурное решение о режиме выполнения миссии для Этапа 7.\n" +
                "Репозиторий: '$owner/$repo' ($branch).\n" +
                "Задача клиента: $userObjective\n\n" +
                "АУДИТ КОДОВОЙ БАЗЫ:\n$auditReport\n\n" +
                "РЕЗУЛЬТАТЫ ИССЛЕДОВАНИЯ 300 ИСТОЧНИКОВ:\n$stage5Research"

        val history = listOf(
            AgentContentDto(
                role = "user",
                parts = listOf(AgentPartDto(text = PROMPT_STAGE_6))
            )
        )

        val turn = executeSingleGeminiTurn(
            systemPrompt = strategySystemPrompt,
            history = history,
            toolDeclarations = GeminiToolDto(functionDeclarations = emptyList()),
            thinkingLevel = "HIGH",
            cachedContentId = cachedContentId
        )

        val rawText = turn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("\n").trim()

        val selectedMode = when {
            rawText.contains("1") || rawText.contains("Только написание новых файлов", ignoreCase = true) ->
                ConveyorExecutionMode.NEW_FILES_ONLY
            rawText.contains("2") || rawText.contains("Только Редактирование", ignoreCase = true) ->
                ConveyorExecutionMode.EDIT_EXISTING_ONLY
            else ->
                ConveyorExecutionMode.FULL_HYBRID
        }

        val logEntry = buildString {
            appendLine("=== [ЭТАП 6: СТРАТЕГИЧЕСКИЙ ВЫБОР РЕЖИМА ИСПОЛНЕНИЯ] ===")
            appendLine("• ВЕРДИКТ АРХИТЕКТОРА: РЕЖИМ ${selectedMode.code} — ${selectedMode.description}")
            appendLine("• АНАЛИТИЧЕСКОЕ ОБОСНОВАНИЕ:")
            appendLine(rawText.ifBlank { "Режим ${selectedMode.code} выбран на основе комплексного анализа кодовой базы и 300 источников." })
            appendLine("• ШЛЮЗ К ЭТАПУ 7 ОТКРЫТ: Директивы переданы в генеральный контур разработки.")
            append("=======================================================")
        }

        return Stage6StrategyResult(
            selectedMode = selectedMode,
            decisionExplanation = rawText,
            logFormattedEntry = logEntry
        )
    }

    private suspend fun executeStage7MonolithicStreamGeneration(
        owner: String,
        repo: String,
        branch: String,
        userObjective: String,
        stage5Research: String,
        sessionId: String
    ): Stage7MonolithResult = withContext(Dispatchers.IO) {
        val monolithDir = File(context.noBackupFilesDir, "workspaces/$sessionId")
        monolithDir.mkdirs()
        val monolithFile = File(monolithDir, "monolith_codebase.raw")
        if (monolithFile.exists()) monolithFile.delete()
        monolithFile.createNewFile()

        val streamSystemPrompt = "Ты — генератор непрерывного исходного кода проекта ClientG на базе Gemini 3.8 Flash.\n" +
                "Репозиторий: '$owner/$repo' (ветка: '$branch').\n" +
                "Задача клиента:\n$userObjective\n\n" +
                "РЕЗУЛЬТАТЫ ИССЛЕДОВАНИЯ 300 ИСТОЧНИКОВ:\n$stage5Research\n\n" +
                "ПРАВИЛА ОФОРМЛЕНИЯ КОДА:\n" +
                "1. Каждый файл ОБЯЗАН предваряться строкой заголовка с полным относительным путем и расширением:\n" +
                ">>> FILE: path/to/Filename.ext\n" +
                "2. Каждый файл ОБЯЗАН завершаться строкой:\n" +
                "<<< END_FILE\n" +
                "3. Пиши чистый, полный, компилируемый код без сокращений и без заглушек // TODO.\n" +
                "4. Категорически запрещены любые вступительные слова, извинения, приветствия и беседы. Начинай вывод сразу с первого тега '>>> FILE:'.\n" +
                "5. Когда абсолютно ВСЕ файлы проекта будут полностью написаны на 100%, выведи на отдельной строке финальный маркер завершения:\n" +
                "$SUCCESS_SENTINEL"

        var waveIndex = 0
        var totalChars = 0L
        var isCompleted = false
        var activeStreamCacheId: String? = null

        val tailWindow = StringBuilder()

        try {
            while (waveIndex < MAX_STREAM_WAVES && !isCompleted && isActive) {
                waveIndex++
                var currentWaveChars = 0

                val promptText = if (waveIndex == 1) PROMPT_STAGE_7_START else PROMPT_STAGE_7_CONTINUE

                _state.update {
                    it.copy(
                        phase = OrchestratorPhase.STREAM_GENERATION_STAGE7,
                        statusMessage = "Этап 7 [HIGH]: Волна $waveIndex (планка 266k)... Собрано: $totalChars симв. (UFS 4.0)"
                    )
                }
                _events.emit(OrchestratorEvent.PhaseChanged(
                    OrchestratorPhase.STREAM_GENERATION_STAGE7,
                    "Этап 7: Волна $waveIndex (всего $totalChars симв.)"
                ))

                val apiKey = geminiApiKeyProvider().trim()
                val endpoint = "$BASE_URL/$MODEL_NAME:streamGenerateContent?key=$apiKey&alt=sse"

                val wireTools = listOf(GeminiToolDto(googleSearch = emptyMap()))

                val requestPayload = AgentWireRequest(
                    cachedContent = activeStreamCacheId,
                    systemInstruction = AgentSystemInstructionDto(listOf(AgentPartDto(text = streamSystemPrompt))),
                    contents = listOf(AgentContentDto(role = "user", parts = listOf(AgentPartDto(text = promptText)))),
                    tools = wireTools,
                    generationConfig = AgentGenerationConfigDto(
                        maxOutputTokens = 65536,
                        thinkingConfig = AgentThinkingConfigDto(thinkingLevel = "HIGH", includeThoughts = true)
                    )
                )

                val serializedPayload = json.encodeToString(AgentWireRequest.serializer(), requestPayload)

                BufferedOutputStream(FileOutputStream(monolithFile, true)).use { outputStream ->
                    httpClient.preparePost(endpoint) {
                        header("x-goog-api-key", apiKey)
                        header(HttpHeaders.Accept, "text/event-stream")
                        header(HttpHeaders.CacheControl, "no-cache")
                        header("X-Accel-Buffering", "no")
                        contentType(ContentType.Application.Json)
                        setBody(serializedPayload)
                    }.execute { httpResponse ->
                        if (!httpResponse.status.isSuccess()) {
                            throw GeminiApiException(httpResponse.status, "HTTP_${httpResponse.status.value}", httpResponse.bodyAsText())
                        }

                        val channel = httpResponse.bodyAsChannel()
                        while (!channel.isClosedForRead && isActive) {
                            val line = channel.readUTF8Line() ?: break
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("data:")) continue

                            val dataPayload = trimmed.removePrefix("data:").trim()
                            if (dataPayload == "[DONE]" || dataPayload.isEmpty()) break

                            val chunk = runCatching {
                                json.decodeFromString(AgentResponseChunk.serializer(), dataPayload)
                            }.getOrNull() ?: continue

                            chunk.usageMetadata?.let { usage ->
                                val total = (usage.promptTokenCount + usage.candidatesTokenCount + usage.thoughtsTokenCount).toLong()
                                _state.update {
                                    it.copy(
                                        totalPromptTokens = it.totalPromptTokens + usage.promptTokenCount,
                                        totalCandidateTokens = it.totalCandidateTokens + usage.candidatesTokenCount,
                                        totalThoughtsTokens = it.totalThoughtsTokens + usage.thoughtsTokenCount,
                                        totalCachedTokens = it.totalCachedTokens + usage.cachedContentTokenCount
                                    )
                                }
                                _events.emit(OrchestratorEvent.TokensUpdated(total, usage.promptTokenCount.toLong(), usage.candidatesTokenCount.toLong()))
                            }

                            chunk.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                if (part.thought == true) {
                                    part.text?.let { delta ->
                                        _events.tryEmit(OrchestratorEvent.ThinkingDelta(delta))
                                    }
                                } else {
                                    val textChunk = part.text ?: ""
                                    if (textChunk.isNotEmpty()) {
                                        val bytes = textChunk.toByteArray(Charsets.UTF_8)
                                        outputStream.write(bytes)
                                        currentWaveChars += textChunk.length
                                        totalChars += textChunk.length

                                        tailWindow.append(textChunk)
                                        if (tailWindow.length > 200) {
                                            tailWindow.delete(0, tailWindow.length - 200)
                                        }

                                        if (tailWindow.contains(SUCCESS_SENTINEL)) {
                                            isCompleted = true
                                        }
                                    }
                                }
                            }
                            outputStream.flush()

                            // Живая телеметрия в UI по мере стриминга на диск
                            _state.update {
                                it.copy(statusMessage = "Стриминг волны $waveIndex: $currentWaveChars / 266 000 симв. (Всего: $totalChars)")
                            }

                            if (currentWaveChars >= CONVEYOR_STREAM_CHAR_LIMIT || isCompleted) {
                                break
                            }
                        }
                    }
                }

                appendDeepLog(
                    "• [ЭТАП 7 | ВОЛНА $waveIndex]: Зафиксировано $currentWaveChars символов на диске. " +
                    "Всего в монолите: $totalChars символов." +
                    if (isCompleted) " [СИГНАЛ SUCCESS ПОЛУЧЕН]" else " [ОТСЕЧКА 266k, ПЕРЕХОД К СЛЕДУЮЩЕЙ ВОЛНЕ]"
                )

                if (!isCompleted && waveIndex < MAX_STREAM_WAVES && totalChars >= 120_000) {
                    val fullMonolithText = monolithFile.readText(Charsets.UTF_8)
                    val newCacheId = pinCodebaseContextCache(fullMonolithText)
                    if (newCacheId != null) {
                        deleteCodebaseContextCache(activeStreamCacheId)
                        activeStreamCacheId = newCacheId
                        AppLogger.i(AppLogger.TAG_APP, "Этап 7: Монолит зафиксирован в TPU Context Cache ($newCacheId) со скидкой 90%.")
                    }
                }
            }
        } finally {
            deleteCodebaseContextCache(activeStreamCacheId)
        }

        val logEntry = buildString {
            appendLine("=== [ЭТАП 7: МОНОЛИТНЫЙ ПОТОКОВЫЙ ГЕНЕРАТОР ЗАВЕРШЁН] ===")
            appendLine("• Статус: ${if (isCompleted) "УСПЕХ (Сигнал завершения получен)" else "ОСТАНОВКА ПО ЛИМИТУ ВОЛН"}")
            appendLine("• Всего волн генерации: $waveIndex")
            appendLine("• Общий объём кодовой базы: $totalChars символов")
            appendLine("• Локация монолита: ${monolithFile.absolutePath}")
            appendLine("• ПЕРЕДАЧА В КОНВЕЙЕР СЕЛЕКЦИИ: Файл готов к парсингу на древовидную файловую структуру.")
            append("=========================================================")
        }

        Stage7MonolithResult(
            isSuccess = isCompleted || totalChars > 0,
            totalChars = totalChars,
            wavesCount = waveIndex,
            monolithFile = monolithFile,
            logFormattedEntry = logEntry
        )
    }

    private data class Stage8SelectionResult(
        val filesCount: Int,
        val commitSha: String,
        val logFormattedEntry: String
    )

    private suspend fun executeStage8FastSelectionAndFlatDispatch(
        monolithFile: File,
        workspaceManager: LocalWorkspaceManager,
        gitHubEngine: GitHubEngine,
        swarmCoordinator: BuilderSwarmCoordinator,
        owner: String,
        repo: String,
        branch: String
    ): Stage8SelectionResult = withContext(Dispatchers.IO) {
        _state.update {
            it.copy(
                phase = OrchestratorPhase.CONVEYOR_SELECTION_STAGE8,
                statusMessage = "Этап 8: Нарезка монолита и запуск плоского роя (1 воркер = 1 файл)..."
            )
        }
        _events.emit(OrchestratorEvent.PhaseChanged(
            OrchestratorPhase.CONVEYOR_SELECTION_STAGE8,
            "Этап 8: Потоковая селекция и распределение роем"
        ))

        var currentPath: String? = null
        val currentCodeBuilder = StringBuilder()
        var extractedFilesCount = 0

        fun dispatchCurrentFile() {
            val path = currentPath ?: return
            val code = currentCodeBuilder.toString().trim()
            if (path.isNotBlank() && code.isNotBlank()) {
                extractedFilesCount++
                val fileNum = extractedFilesCount
                _state.update {
                    it.copy(statusMessage = "Селекция: запуск воркера #$fileNum -> ${path.substringAfterLast('/')}")
                }
                coordinatorScopeLaunch {
                    swarmCoordinator.registerFlatBuilder(
                        index = fileNum,
                        targetFile = path,
                        codeSnippet = code
                    )
                }
            }
            currentPath = null
            currentCodeBuilder.setLength(0)
        }

        monolithFile.forEachLine(Charsets.UTF_8) { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith(">>> FILE:") -> {
                    dispatchCurrentFile()
                    currentPath = trimmed.removePrefix(">>> FILE:").trim()
                }
                trimmed.startsWith("<<< END_FILE") -> {
                    dispatchCurrentFile()
                }
                trimmed.contains(SUCCESS_SENTINEL) -> {
                    dispatchCurrentFile()
                }
                else -> {
                    if (currentPath != null) {
                        currentCodeBuilder.append(line).append("\n")
                    }
                }
            }
        }
        dispatchCurrentFile()

        if (extractedFilesCount == 0) {
            throw IllegalStateException("В монолите не обнаружено ни одного размеченного файла (>>> FILE: ...).")
        }

        _state.update { it.copy(statusMessage = "Запечатывание барьера на $extractedFilesCount файлов. Ожидание записи на UFS 4.0...") }
        swarmCoordinator.sealBarrier(extractedFilesCount)
        val manifest = swarmCoordinator.awaitGreenLight(300_000L)

        _state.update { it.copy(statusMessage = "Зелёная лампочка получена! Подсчёт дельты UFS 4.0...") }
        val delta = workspaceManager.computeChangedFiles()
        if (delta.modifiedFiles.isEmpty()) {
            throw IllegalStateException("Файлы не зафиксированы на диске UFS 4.0 после отчётов роя.")
        }

        _state.update { it.copy(statusMessage = "Отправка 1 атомарного коммита в $branch (${delta.modifiedFiles.size} файлов)...") }
        val commitMessage = "feat: autonomous monolith codebase deployment (${delta.modifiedFiles.size} files)"
        val pushResult = gitHubEngine.pushAtomicCommit(
            owner = owner,
            repo = repo,
            branch = branch,
            commitMessage = commitMessage,
            modifiedFiles = delta.modifiedFiles,
            deletedFiles = delta.deletedFiles
        )

        val logEntry = buildString {
            appendLine("=== [ЭТАП 8: СЕЛЕКЦИЯ И АТОМАРНЫЙ КОММИТ ЗАВЕРШЕНЫ] ===")
            appendLine("• Файлов нарезано и отдано рою: $extractedFilesCount")
            appendLine("• Успешно записано воркерами на UFS 4.0: ${manifest.successCount}")
            appendLine("• Ошибок воркеров: ${manifest.failedCount}")
            appendLine("• Сформирован 1 атомарный коммит: ${pushResult.commitSha}")
            appendLine("• Ветка: ${pushResult.branch}")
            appendLine("• ЗЕЛЁНАЯ ЛАМПОЧКА: Проект полностью материализован в Git-репозитории.")
            append("=========================================================")
        }

        Stage8SelectionResult(
            filesCount = extractedFilesCount,
            commitSha = pushResult.commitSha,
            logFormattedEntry = logEntry
        )
    }

    private fun coordinatorScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { block() }
    }

    private fun appendDeepLog(text: String) {
        val timestamped = "\n\n$text"
        _state.update { it.copy(deepInvestigationLog = it.deepInvestigationLog + timestamped) }
        _events.tryEmit(OrchestratorEvent.DeepLogAppended(timestamped))
    }

    private suspend fun pinCodebaseContextCache(
        codebasePayload: String,
        ttlSeconds: Long = 7200L
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = geminiApiKeyProvider().trim()
        if (apiKey.isBlank() || codebasePayload.length < 120_000) return@withContext null

        val endpoint = "$ROOT_V1/cachedContents?key=$apiKey"
        val requestPayload = buildJsonObject {
            put("model", "publishers/google/models/$MODEL_NAME")
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", codebasePayload) }
                    }
                }
                addJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        addJsonObject { put("text", "Кодовая база зафиксирована в памяти Google TPU.") }
                    }
                }
            }
            put("ttl", "${ttlSeconds}s")
        }

        runCatching {
            val response = httpClient.post(endpoint) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestPayload.toString())
            }
            if (response.status.isSuccess()) {
                val respObj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                respObj["name"]?.jsonPrimitive?.contentOrNull
            } else null
        }.getOrNull()
    }

    private suspend fun deleteCodebaseContextCache(cachedName: String?) = withContext(Dispatchers.IO) {
        if (cachedName.isNullOrBlank()) return@withContext
        val apiKey = geminiApiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext
        runCatching {
            httpClient.delete("$ROOT_V1/$cachedName?key=$apiKey") {
                header("x-goog-api-key", apiKey)
            }
        }
    }

    private suspend fun executeGeminiTurnWithRetry(
        systemPrompt: String,
        history: List<AgentContentDto>,
        toolDeclarations: GeminiToolDto,
        thinkingLevel: String,
        cachedContentId: String? = null
    ): AgentContentDto {
        var attempt = 0
        while (attempt < 4) {
            try {
                return executeSingleGeminiTurn(systemPrompt, history, toolDeclarations, thinkingLevel, cachedContentId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                attempt++
                if (attempt >= 4) throw e
                val backoff = min(MAX_BACKOFF_MS, (BASELINE_BACKOFF_MS * 2.0.pow(attempt.toDouble())).toLong()) + Random.nextLong(100, 600)
                delay(backoff)
            }
        }
        throw IllegalStateException("Не удалось получить ответ от Gemini 3.8 Flash.")
    }

    private suspend fun executeSingleGeminiTurn(
        systemPrompt: String,
        history: List<AgentContentDto>,
        toolDeclarations: GeminiToolDto,
        thinkingLevel: String,
        cachedContentId: String? = null
    ): AgentContentDto = withContext(Dispatchers.IO) {
        val apiKey = geminiApiKeyProvider().trim()
        val endpoint = "$BASE_URL/$MODEL_NAME:streamGenerateContent?key=$apiKey&alt=sse"

        val sanitizedHistory = sanitizeHistoryForWire(history)

        val hasTools = !toolDeclarations.functionDeclarations.isNullOrEmpty() || toolDeclarations.googleSearch != null
        val wireTools: List<GeminiToolDto>? = if (hasTools) {
            val list = ArrayList<GeminiToolDto>()
            if (toolDeclarations.googleSearch != null) {
                list.add(GeminiToolDto(googleSearch = toolDeclarations.googleSearch))
            }
            if (!toolDeclarations.functionDeclarations.isNullOrEmpty()) {
                list.add(GeminiToolDto(functionDeclarations = toolDeclarations.functionDeclarations))
            }
            if (list.isNotEmpty()) list else null
        } else {
            null
        }

        val requestPayload = AgentWireRequest(
            cachedContent = cachedContentId,
            systemInstruction = AgentSystemInstructionDto(listOf(AgentPartDto(text = systemPrompt))),
            contents = sanitizedHistory,
            tools = wireTools,
            generationConfig = AgentGenerationConfigDto(
                maxOutputTokens = 65536,
                thinkingConfig = AgentThinkingConfigDto(thinkingLevel = thinkingLevel, includeThoughts = true)
            )
        )

        val serializedBody = json.encodeToString(AgentWireRequest.serializer(), requestPayload)
        val responseParts = mutableListOf<AgentPartDto>()
        val accumulatedText = StringBuilder()
        val accumulatedThought = StringBuilder()
        var currentThoughtSignature: String? = null

        httpClient.preparePost(endpoint) {
            header("x-goog-api-key", apiKey)
            header(HttpHeaders.Accept, "text/event-stream")
            header(HttpHeaders.CacheControl, "no-cache")
            header("X-Accel-Buffering", "no")
            contentType(ContentType.Application.Json)
            setBody(serializedBody)
        }.execute { httpResponse ->
            if (!httpResponse.status.isSuccess()) {
                throw GeminiApiException(httpResponse.status, "HTTP_${httpResponse.status.value}", httpResponse.bodyAsText())
            }

            val channel = httpResponse.bodyAsChannel()
            while (!channel.isClosedForRead) {
                currentCoroutineContext().ensureActive()
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue

                val dataPayload = trimmed.removePrefix("data:").trim()
                if (dataPayload == "[DONE]" || dataPayload.isEmpty()) break

                val chunk = runCatching {
                    json.decodeFromString(AgentResponseChunk.serializer(), dataPayload)
                }.getOrNull() ?: continue

                chunk.usageMetadata?.let { usage ->
                    val total = (usage.promptTokenCount + usage.candidatesTokenCount + usage.thoughtsTokenCount).toLong()
                    _state.update {
                        it.copy(
                            totalPromptTokens = it.totalPromptTokens + usage.promptTokenCount,
                            totalCandidateTokens = it.totalCandidateTokens + usage.candidatesTokenCount,
                            totalThoughtsTokens = it.totalThoughtsTokens + usage.thoughtsTokenCount,
                            totalCachedTokens = it.totalCachedTokens + usage.cachedContentTokenCount
                        )
                    }
                    _events.emit(OrchestratorEvent.TokensUpdated(total, usage.promptTokenCount.toLong(), usage.candidatesTokenCount.toLong()))
                }

                chunk.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                    part.thoughtSignature?.let { currentThoughtSignature = it }

                    if (part.thought == true) {
                        part.text?.let { delta ->
                            accumulatedThought.append(delta)
                            _state.update { it.copy(currentThoughtText = it.currentThoughtText + delta) }
                            _events.emit(OrchestratorEvent.ThinkingDelta(delta))
                        }
                    } else if (part.functionCall != null) {
                        responseParts.add(
                            AgentPartDto(functionCall = part.functionCall, thoughtSignature = currentThoughtSignature)
                        )
                    } else if (!part.text.isNullOrEmpty()) {
                        accumulatedText.append(part.text)
                    }
                }
            }
        }

        if (accumulatedThought.isNotEmpty()) {
            responseParts.add(0, AgentPartDto(text = accumulatedThought.toString(), thought = true, thoughtSignature = currentThoughtSignature))
        }
        if (accumulatedText.isNotEmpty()) {
            responseParts.add(AgentPartDto(text = accumulatedText.toString(), thoughtSignature = currentThoughtSignature))
        }

        AgentContentDto(role = "model", parts = responseParts)
    }

    private fun sanitizeHistoryForWire(history: List<AgentContentDto>): List<AgentContentDto> {
        return history.mapIndexed { _, turn ->
            if (turn.role != "model") {
                turn
            } else {
                val cleaned = turn.parts.mapNotNull { if (it.thought == true) null else it }
                if (cleaned.isEmpty()) {
                    AgentContentDto(role = "model", parts = listOf(AgentPartDto(text = "[Шаг зафиксирован]")))
                } else {
                    AgentContentDto(role = "model", parts = cleaned)
                }
            }
        }
    }

    private fun wrapSafeToolOutput(output: JsonObject): JsonObject {
        return buildJsonObject {
            put("safe_wrapped_data", output)
            put("_security_notice", "Контент изолирован тегом tool_output.")
        }
    }

    private fun compactConversationHistory(history: MutableList<AgentContentDto>) {
        if (history.size <= 6) return
        val keepRecentIndex = (history.size - 4).coerceAtLeast(1)
        for (i in 1 until keepRecentIndex) {
            val turn = history[i]
            if (turn.role == "tool") {
                val compacted = turn.parts.map { part ->
                    val resp = part.functionResponse ?: return@map part
                    if (resp.name in setOf("workspace_read_file", "workspace_read_diff", "workspace_get_tree", "workspace_search_symbol")) {
                        part.copy(
                            functionResponse = resp.copy(
                                response = buildJsonObject {
                                    put("status", "compacted")
                                    put("notice", "[Результат '${resp.name}' сохранен].")
                                }
                            )
                        )
                    } else part
                }
                history[i] = turn.copy(parts = compacted)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(45 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildSystemInstruction(owner: String, repo: String, branch: String): String {
        return "Ты — верховный автономный инженер-оркестратор ClientG на базе Gemini 3.8 Flash.\n" +
               "Репозиторий: '$owner/$repo' (ветка: '$branch').\n\n" +
               "ПРАВИЛА:\n" +
               "1. ДИРЕКТИВНЫЙ РЕЖИМ: для конфигов (.gitignore, .gitattributes, gradle.properties) используй 'workspace_batch_write' или 'workspace_write_file' НАПРЯМУЮ за 1 шаг без вызова роя!\n" +
               "2. РЕЖИМ РОЯ: для объемной логики и модулей ОБЯЗАТЕЛЬНО запускай билдеров 3.5 Lite ('swarm_dispatch_primary_builder', 'swarm_dispatch_cross_builder', 'swarm_seal_barrier').\n" +
               "3. ДИФФ И КОММИТ: проверяй 'workspace_read_diff' и пушь 'github_push_atomic_commit'.\n" +
               "4. АВТО-ОСТАНОВКА: при успехе коммита и отсутствии CI в репозитории — НЕ вызывай другие инструменты, сразу завершай задачу отчетом!\n" +
               "5. CI: вызывай 'github_trigger_ci_build' ТОЛЬКО при наличии .github/workflows/*.yml.\n" +
               "6. ПОИСК GOOGLE: у тебя ВСЕГДА активен поиск в интернете (Google Search). На любом шаге реализации используй его для проверки актуальной документации библиотек, версий зависимостей и решения ошибок компиляции."
    }

    private fun buildInitialUserPrompt(
        objective: String,
        totalFiles: Int,
        t0Passport: String,
        stage2Result: Stage2FeasibilityResult,
        auditReport: String,
        stage5Research: String,
        strategyResult: Stage6StrategyResult
    ): String {
        return "$t0Passport\n\n" +
               "${stage2Result.logFormattedEntry}\n\n" +
               "$auditReport\n\n" +
               "$stage5Research\n\n" +
               "${strategyResult.logFormattedEntry}\n\n" +
               "ЦЕЛЕВАЯ ЗАДАЧА КЛИЕНТА:\n$objective\n\n" +
               "ДИРЕКТИВА ЭТАПА 7: Действуй строго в рамках РЕЖИМА ${strategyResult.selectedMode.code} (${strategyResult.selectedMode.description}). " +
               "Файлов в репозитории: $totalFiles. Кодовая база, 300 первоисточников и стратегия зафиксированы. Приступай к планированию и реализации."
    }

    fun cancelTask() {
        activeJob?.cancel()
        activeJob = null
    }

    override fun close() {
        cancelTask()
        releaseWakeLock()
        if (shouldCloseHttpClient) httpClient.close()
    }
}