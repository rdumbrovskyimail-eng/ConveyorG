package com.conveyorg.agent

import android.content.Context
import com.conveyorg.data.LocalWorkspaceManager
import com.conveyorg.network.*
import com.conveyorg.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// ====================================================================
// 1. Доменные Модели и Контракты Роя Билдеров
// ====================================================================

enum class BuilderRole {
    PRIMARY_A,
    CROSS_CUTTING_B,
    FLAT_WORKER // Равноправный независимый воркер режима создания файлов
}

enum class BuilderStatus {
    PENDING,
    WAITING_DEPENDENCY,
    RUNNING,
    SUCCESS,
    FAILED,
    ABORTED_UPSTREAM_CORRUPTED
}

data class BuilderTask(
    val taskId: String,
    val role: BuilderRole,
    val stageNumber: Int,
    val targetFile: String,
    val instruction: String,
    val referenceCode: String? = null,
    val dependencyTaskId: String? = null
)

@Serializable
data class BuilderReport(
    val taskId: String,
    val role: String,
    val targetFile: String,
    val status: String,
    val summary: String,
    val filesTouched: List<String>,
    val durationMs: Long,
    val promptTokens: Int = 0,
    val candidateTokens: Int = 0,
    val errorMessage: String? = null
)

@Serializable
data class AllReportsManifest(
    val totalExpected: Int,
    val totalCompleted: Int,
    val successCount: Int,
    val failedCount: Int,
    val abortedCount: Int,
    val reports: List<BuilderReport>,
    val isGreenLightTriggered: Boolean,
    val totalDurationMs: Long,
    val totalTokensBurned: Long
)

data class SwarmState(
    val registeredCount: Int = 0,
    val primaryCountA: Int = 0,
    val crossCountB: Int = 0,
    val flatCount: Int = 0,
    val isSealed: Boolean = false,
    val expectedBarrier: Int = 0,
    val remainingBarrier: Int = 0,
    val isGreenLightOn: Boolean = false,
    val activeWorkersCount: Int = 0,
    val reportsSubmittedCount: Int = 0,
    val circuitBreakerTripped: Boolean = false,
    val statusMessage: String = "Рой билдеров готов"
)

sealed interface SwarmEvent {
    data class TaskRegistered(val taskId: String, val role: BuilderRole, val targetFile: String) : SwarmEvent
    data class TaskStarted(val taskId: String, val role: BuilderRole) : SwarmEvent
    data class TaskAwaitingDependency(val taskId: String, val waitingForTaskId: String) : SwarmEvent
    data class ReportSubmitted(val taskId: String, val status: BuilderStatus, val remaining: Int) : SwarmEvent
    data class GreenLightIgnited(val manifest: AllReportsManifest) : SwarmEvent
    data class CircuitBreakerTripped(val reason: String) : SwarmEvent
}

// ====================================================================
// 2. Внутренние DTO Запросов к Gemini 3.5 Flash-Lite
// ====================================================================

@Serializable
internal data class LiteWireRequest(
    @SerialName("systemInstruction") val systemInstruction: LiteSystemInstructionDto,
    val contents: List<LiteContentDto>,
    val tools: List<GeminiToolDto>,
    @SerialName("generationConfig") val generationConfig: LiteGenerationConfigDto
)

@Serializable
internal data class LiteSystemInstructionDto(val parts: List<LitePartDto>)

@Serializable
internal data class LiteContentDto(
    val role: String,
    val parts: List<LitePartDto>
)

@Serializable
internal data class LitePartDto(
    val text: String? = null,
    @SerialName("thought_signature") val thoughtSignatureSnake: String? = null,
    @SerialName("thoughtSignature") val thoughtSignatureCamel: String? = null,
    @SerialName("functionCall") val functionCall: LiteFunctionCallDto? = null,
    @SerialName("functionResponse") val functionResponse: LiteFunctionResponseDto? = null
) {
    fun resolveThoughtSignature(): String? =
        thoughtSignatureSnake?.takeIf { it.isNotBlank() }
            ?: thoughtSignatureCamel?.takeIf { it.isNotBlank() }
}

@Serializable
internal data class LiteFunctionCallDto(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val id: String? = null
)

@Serializable
internal data class LiteFunctionResponseDto(
    val name: String,
    val response: JsonObject,
    val id: String? = null
)

@Serializable
internal data class LiteGenerationConfigDto(
    val maxOutputTokens: Int = 8192,
    val temperature: Float = 0.2f
)

@Serializable
internal data class LiteUnaryResponse(
    val candidates: List<LiteCandidateDto>? = null,
    val usageMetadata: LiteUsageMetadataDto? = null
)

@Serializable
internal data class LiteCandidateDto(
    val content: LiteContentDto? = null,
    val finishReason: String? = null
)

@Serializable
internal data class LiteUsageMetadataDto(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0
)

// ====================================================================
// 3. Главный Диспетчер Роя: BuilderSwarmCoordinator
// ====================================================================

class BuilderSwarmCoordinator(
    private val context: Context,
    private val workspaceManager: LocalWorkspaceManager,
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val shouldCloseHttpClient: Boolean = true
) : Closeable {

    companion object {
        const val MAX_TOTAL_BUILDERS = 120 // Поддержка масштабных проектов до 100+ файлов
        const val MAX_PRIMARY_BUILDERS_A = 20
        const val MAX_CROSS_BUILDERS_B = 20
        private const val CONCURRENCY_PERMITS = 20 // Высокопроизводительный пул сокетов

        private const val LITE_MODEL_NAME = "gemini-3.5-flash-lite"
        private const val API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val WORKER_TIMEOUT_MS = 90_000L

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
                    connectTimeout = 20_000
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L
                socketTimeoutMillis = 90_000L
                connectTimeoutMillis = 20_000L
            }
        }
    }

    private val _state = MutableStateFlow(SwarmState())
    val state: StateFlow<SwarmState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SwarmEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<SwarmEvent> = _events.asSharedFlow()

    private val coordinatorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val registeredTasks = ConcurrentHashMap<String, BuilderTask>()
    private val taskSignals = ConcurrentHashMap<String, CompletableDeferred<BuilderReport>>()
    private val collectedReports = CopyOnWriteArrayList<BuilderReport>()

    private val concurrencySemaphore = Semaphore(CONCURRENCY_PERMITS)
    private val registrationMutex = Mutex()

    private val atomicRemainingBarrier = AtomicInteger(0)
    private var barrierDeferred = CompletableDeferred<AllReportsManifest>()
    private val failedPrimaryTasksCount = AtomicInteger(0)

    private val swarmStartTime = System.currentTimeMillis()

    /**
     * Высокоскоростная регистрация независимого плоского воркера (1 воркер = 1 файл)
     */
    suspend fun registerFlatBuilder(
        index: Int,
        targetFile: String,
        codeSnippet: String
    ): String = registrationMutex.withLock {
        val currentState = _state.value

        if (currentState.isGreenLightOn) {
            _state.update {
                it.copy(
                    isSealed = false,
                    isGreenLightOn = false,
                    expectedBarrier = 0,
                    remainingBarrier = 0
                )
            }
            barrierDeferred = CompletableDeferred()
        } else if (currentState.isSealed) {
            atomicRemainingBarrier.incrementAndGet()
            _state.update {
                it.copy(
                    expectedBarrier = it.expectedBarrier + 1,
                    remainingBarrier = it.remainingBarrier + 1
                )
            }
        }

        check(currentState.registeredCount < MAX_TOTAL_BUILDERS) {
            "Превышен абсолютный потолок роя ($MAX_TOTAL_BUILDERS воркеров)."
        }

        val taskId = "flat_worker_${index.toString().padStart(2, '0')}_${UUID.randomUUID().toString().take(4)}"
        val instruction = "Ты должен создать этот файл по пути $targetFile в репозитории."
        val task = BuilderTask(
            taskId = taskId,
            role = BuilderRole.FLAT_WORKER,
            stageNumber = index,
            targetFile = targetFile,
            instruction = instruction,
            referenceCode = codeSnippet,
            dependencyTaskId = null
        )

        val signal = CompletableDeferred<BuilderReport>()
        registeredTasks[taskId] = task
        taskSignals[taskId] = signal

        _state.update {
            it.copy(
                registeredCount = it.registeredCount + 1,
                flatCount = it.flatCount + 1,
                statusMessage = "Запущен воркер #${index.toString().padStart(2, '0')}: $targetFile"
            )
        }
        _events.emit(SwarmEvent.TaskRegistered(taskId, BuilderRole.FLAT_WORKER, targetFile))

        coordinatorScope.launch {
            executeWorkerLifecycle(task, signal)
        }

        taskId
    }

    suspend fun registerPrimaryBuilderA(
        stageNumber: Int,
        targetFile: String,
        instruction: String,
        referenceCode: String? = null
    ): String = registrationMutex.withLock {
        val currentState = _state.value

        if (currentState.isGreenLightOn) {
            _state.update {
                it.copy(
                    isSealed = false,
                    isGreenLightOn = false,
                    expectedBarrier = 0,
                    remainingBarrier = 0
                )
            }
            barrierDeferred = CompletableDeferred()
        } else if (currentState.isSealed) {
            atomicRemainingBarrier.incrementAndGet()
            _state.update {
                it.copy(
                    expectedBarrier = it.expectedBarrier + 1,
                    remainingBarrier = it.remainingBarrier + 1
                )
            }
        }

        check(currentState.primaryCountA < MAX_PRIMARY_BUILDERS_A) {
            "Превышен лимит основных билдеров A ($MAX_PRIMARY_BUILDERS_A)."
        }
        check(currentState.registeredCount < MAX_TOTAL_BUILDERS) {
            "Превышен лимит роя ($MAX_TOTAL_BUILDERS)."
        }

        val taskId = "builder_A_stage_${stageNumber}_${UUID.randomUUID().toString().take(6)}"
        val task = BuilderTask(
            taskId = taskId,
            role = BuilderRole.PRIMARY_A,
            stageNumber = stageNumber,
            targetFile = targetFile,
            instruction = instruction,
            referenceCode = referenceCode,
            dependencyTaskId = null
        )

        val signal = CompletableDeferred<BuilderReport>()
        registeredTasks[taskId] = task
        taskSignals[taskId] = signal

        _state.update {
            it.copy(
                registeredCount = it.registeredCount + 1,
                primaryCountA = it.primaryCountA + 1,
                statusMessage = "Зарегистрирован билдер A ($stageNumber): $targetFile"
            )
        }
        _events.emit(SwarmEvent.TaskRegistered(taskId, BuilderRole.PRIMARY_A, targetFile))

        coordinatorScope.launch {
            executeWorkerLifecycle(task, signal)
        }

        taskId
    }

    suspend fun registerCrossCuttingBuilderB(
        dependencyTaskId: String,
        stageNumber: Int,
        targetFile: String,
        instruction: String
    ): String = registrationMutex.withLock {
        val currentState = _state.value

        if (currentState.isGreenLightOn) {
            _state.update {
                it.copy(
                    isSealed = false,
                    isGreenLightOn = false,
                    expectedBarrier = 0,
                    remainingBarrier = 0
                )
            }
            barrierDeferred = CompletableDeferred()
        } else if (currentState.isSealed) {
            atomicRemainingBarrier.incrementAndGet()
            _state.update {
                it.copy(
                    expectedBarrier = it.expectedBarrier + 1,
                    remainingBarrier = it.remainingBarrier + 1
                )
            }
        }

        check(currentState.crossCountB < MAX_CROSS_BUILDERS_B) {
            "Превышен лимит сквозных билдеров B ($MAX_CROSS_BUILDERS_B)."
        }
        check(currentState.registeredCount < MAX_TOTAL_BUILDERS) {
            "Превышен лимит роя ($MAX_TOTAL_BUILDERS)."
        }
        check(taskSignals.containsKey(dependencyTaskId)) {
            "Зависимость '$dependencyTaskId' не найдена в реестре задач."
        }

        val taskId = "builder_B_peer_${stageNumber}_${UUID.randomUUID().toString().take(6)}"
        val task = BuilderTask(
            taskId = taskId,
            role = BuilderRole.CROSS_CUTTING_B,
            stageNumber = stageNumber,
            targetFile = targetFile,
            instruction = instruction,
            referenceCode = null,
            dependencyTaskId = dependencyTaskId
        )

        val signal = CompletableDeferred<BuilderReport>()
        registeredTasks[taskId] = task
        taskSignals[taskId] = signal

        _state.update {
            it.copy(
                registeredCount = it.registeredCount + 1,
                crossCountB = it.crossCountB + 1,
                statusMessage = "Зарегистрирован сквозной билдер B -> ожидает $dependencyTaskId"
            )
        }
        _events.emit(SwarmEvent.TaskRegistered(taskId, BuilderRole.CROSS_CUTTING_B, targetFile))

        coordinatorScope.launch {
            executeWorkerLifecycle(task, signal)
        }

        taskId
    }

    suspend fun sealBarrier(expectedCount: Int): Boolean = registrationMutex.withLock {
        check(expectedCount in 1..MAX_TOTAL_BUILDERS) {
            "Недопустимое значение барьера: $expectedCount (Лимит 1..$MAX_TOTAL_BUILDERS)"
        }

        val completedNow = collectedReports.size
        val remaining = maxOf(0, expectedCount - completedNow)
        atomicRemainingBarrier.set(remaining)

        if (barrierDeferred.isCompleted) {
            barrierDeferred = CompletableDeferred()
        }

        _state.update {
            it.copy(
                isSealed = true,
                expectedBarrier = expectedCount,
                remainingBarrier = remaining,
                statusMessage = "Барьер запечатан на $expectedCount задач. Осталось: $remaining"
            )
        }

        AppLogger.i(AppLogger.TAG_APP, "SwarmCoordinator: Барьер запечатан ($expectedCount задач, осталось=$remaining)")

        if (remaining == 0) {
            triggerGreenLight()
        }
        true
    }

    suspend fun awaitGreenLight(timeoutMs: Long = 300_000L): AllReportsManifest {
        return withTimeout(timeoutMs) {
            barrierDeferred.await()
        }
    }

    private suspend fun executeWorkerLifecycle(
        task: BuilderTask,
        taskSignal: CompletableDeferred<BuilderReport>
    ) {
        val workerStartTime = System.currentTimeMillis()

        if (task.role == BuilderRole.CROSS_CUTTING_B && task.dependencyTaskId != null) {
            _events.emit(SwarmEvent.TaskAwaitingDependency(task.taskId, task.dependencyTaskId))
            val parentSignal = taskSignals[task.dependencyTaskId]

            val parentReport = parentSignal?.await()
            if (parentReport == null || parentReport.status != BuilderStatus.SUCCESS.name) {
                AppLogger.w(AppLogger.TAG_APP, "SwarmWorker [${task.taskId}]: Предшественник упал. Аннулирование.")
                val abortReport = BuilderReport(
                    taskId = task.taskId,
                    role = task.role.name,
                    targetFile = task.targetFile,
                    status = BuilderStatus.ABORTED_UPSTREAM_CORRUPTED.name,
                    summary = "Аннулировано из-за родительской задачи ${task.dependencyTaskId}.",
                    filesTouched = emptyList(),
                    durationMs = System.currentTimeMillis() - workerStartTime,
                    errorMessage = "Upstream task failure"
                )
                finalizeTaskReport(abortReport, taskSignal)
                return
            }
        }

        concurrencySemaphore.withPermit {
            _state.update { it.copy(activeWorkersCount = it.activeWorkersCount + 1) }
            _events.emit(SwarmEvent.TaskStarted(task.taskId, task.role))

            try {
                withTimeout(WORKER_TIMEOUT_MS) {
                    val report = runWorkerReActLoop(task, workerStartTime)
                    finalizeTaskReport(report, taskSignal)
                }
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_APP, "SwarmWorker [${task.taskId}]: Ошибка: ${e.message}", e)
                val failReport = BuilderReport(
                    taskId = task.taskId,
                    role = task.role.name,
                    targetFile = task.targetFile,
                    status = BuilderStatus.FAILED.name,
                    summary = "Сбой: ${e.localizedMessage}",
                    filesTouched = emptyList(),
                    durationMs = System.currentTimeMillis() - workerStartTime,
                    errorMessage = e.message
                )
                if (task.role == BuilderRole.PRIMARY_A) {
                    failedPrimaryTasksCount.incrementAndGet()
                }
                finalizeTaskReport(failReport, taskSignal)
            } finally {
                _state.update { it.copy(activeWorkersCount = maxOf(0, it.activeWorkersCount - 1)) }
            }
        }
    }

    private suspend fun runWorkerReActLoop(task: BuilderTask, startTime: Long): BuilderReport {
        val systemPrompt = "Ты — быстрый инженер-сборщик ClientG на базе Gemini 3.5 Flash-Lite.\n" +
                "Твоя цель: записать чистовой файл строго по пути '${task.targetFile}'.\n" +
                "ПЕСОЧНИЦА ИНСТРУМЕНТОВ:\n" +
                "1. sandbox_write_file(path, content) — записать чистовой код на UFS 4.0.\n" +
                "2. sandbox_submit_report(status, summary) — сдать отчёт о создании файла.\n" +
                "Запрещено писать код в чат — записывай его строго через 'sandbox_write_file'."

        val initialPrompt = buildString {
            appendLine("Целевой файл: ${task.targetFile}")
            appendLine(task.instruction)
            if (!task.referenceCode.isNullOrBlank()) {
                appendLine()
                appendLine("КОД ДЛЯ ЗАПИСИ:")
                appendLine(task.referenceCode)
            }
        }

        val conversation = mutableListOf<LiteContentDto>()
        conversation.add(LiteContentDto(role = "user", parts = listOf(LitePartDto(text = initialPrompt))))

        val touchedFiles = mutableListOf<String>()
        var reportOutcome: BuilderReport? = null
        var totalPromptTokens = 0
        var totalCandidateTokens = 0
        var steps = 0

        while (steps < 5 && reportOutcome == null) {
            steps++
            val response = executeLiteUnaryWithRetry(systemPrompt, conversation)
            totalPromptTokens += response.usageMetadata?.promptTokenCount ?: 0
            totalCandidateTokens += response.usageMetadata?.candidatesTokenCount ?: 0

            val candidate = response.candidates?.firstOrNull() ?: break
            val candidateContent = candidate.content ?: break
            val functionCallPart = candidateContent.parts.firstOrNull { it.functionCall != null }
            val functionCall = functionCallPart?.functionCall

            if (functionCall != null) {
                val signature = functionCallPart.resolveThoughtSignature()
                    ?: candidateContent.parts.firstOrNull { it.resolveThoughtSignature() != null }?.resolveThoughtSignature()

                val preservedModelParts = candidateContent.parts.map { part ->
                    if (part.functionCall != null) {
                        part.copy(
                            thoughtSignatureSnake = signature ?: part.resolveThoughtSignature(),
                            thoughtSignatureCamel = null
                        )
                    } else part
                }

                conversation.add(LiteContentDto(role = "model", parts = preservedModelParts))

                val toolResult = when (functionCall.name) {
                    "sandbox_read_file" -> {
                        val path = functionCall.args["path"]?.jsonPrimitive?.content ?: task.targetFile
                        val readRes = workspaceManager.readFile(path)
                        buildJsonObject {
                            put("status", "success")
                            put("content", readRes.content)
                        }
                    }
                    "sandbox_write_file" -> {
                        val path = functionCall.args["path"]?.jsonPrimitive?.content ?: task.targetFile
                        val content = functionCall.args["content"]?.jsonPrimitive?.content ?: task.referenceCode ?: ""

                        val cleanContent = content.trim()
                            .replace(Regex("^```[a-zA-Z0-9_-]*\\r?\\n"), "")
                            .replace(Regex("\\r?\\n```$"), "")
                            .trim()

                        workspaceManager.writeFileAtomic(path, cleanContent.toByteArray(Charsets.UTF_8))
                        if (path !in touchedFiles) touchedFiles.add(path)

                        buildJsonObject {
                            put("status", "success")
                            put("written_bytes", cleanContent.length)
                        }
                    }
                    "sandbox_submit_report" -> {
                        val statusStr = functionCall.args["status"]?.jsonPrimitive?.content ?: "SUCCESS"
                        val summaryStr = functionCall.args["summary"]?.jsonPrimitive?.content ?: "Файл успешно создан."

                        reportOutcome = BuilderReport(
                            taskId = task.taskId,
                            role = task.role.name,
                            targetFile = task.targetFile,
                            status = if (statusStr.equals("SUCCESS", ignoreCase = true)) BuilderStatus.SUCCESS.name else BuilderStatus.FAILED.name,
                            summary = summaryStr,
                            filesTouched = touchedFiles,
                            durationMs = System.currentTimeMillis() - startTime,
                            promptTokens = totalPromptTokens,
                            candidateTokens = totalCandidateTokens
                        )

                        buildJsonObject { put("status", "acknowledged") }
                    }
                    else -> buildJsonObject { put("error", "Неизвестный инструмент.") }
                }

                conversation.add(
                    LiteContentDto(
                        role = "tool",
                        parts = listOf(
                            LitePartDto(
                                functionResponse = LiteFunctionResponseDto(
                                    name = functionCall.name,
                                    response = toolResult,
                                    id = functionCall.id
                                )
                            )
                        )
                    )
                )
            } else {
                // Если воркер не вызвал инструмент, но у нас есть эталонный код — записываем его напрямую
                if (touchedFiles.isEmpty() && !task.referenceCode.isNullOrBlank()) {
                    workspaceManager.writeFileAtomic(task.targetFile, task.referenceCode.toByteArray(Charsets.UTF_8))
                    touchedFiles.add(task.targetFile)
                }
                break
            }
        }

        return reportOutcome ?: BuilderReport(
            taskId = task.taskId,
            role = task.role.name,
            targetFile = task.targetFile,
            status = if (touchedFiles.isNotEmpty()) BuilderStatus.SUCCESS.name else BuilderStatus.FAILED.name,
            summary = "Файл зафиксирован на диске (байт: ${task.referenceCode?.length ?: 0}).",
            filesTouched = touchedFiles,
            durationMs = System.currentTimeMillis() - startTime,
            promptTokens = totalPromptTokens,
            candidateTokens = totalCandidateTokens
        )
    }

    private suspend fun finalizeTaskReport(
        report: BuilderReport,
        signal: CompletableDeferred<BuilderReport>
    ) {
        signal.complete(report)
        collectedReports.add(report)

        if (failedPrimaryTasksCount.get() >= 3) {
            _state.update { it.copy(circuitBreakerTripped = true) }
            _events.emit(SwarmEvent.CircuitBreakerTripped("Превышен лимит сбоев основных задач A."))
        }

        val remaining = atomicRemainingBarrier.decrementAndGet()
        _state.update {
            it.copy(
                remainingBarrier = maxOf(0, remaining),
                reportsSubmittedCount = it.reportsSubmittedCount + 1,
                statusMessage = "Отчёт [${report.targetFile.substringAfterLast('/')}]: ${report.status} (Осталось: ${maxOf(0, remaining)})"
            )
        }
        _events.emit(SwarmEvent.ReportSubmitted(report.taskId, BuilderStatus.valueOf(report.status), maxOf(0, remaining)))

        if (_state.value.isSealed && remaining <= 0) {
            triggerGreenLight()
        }
    }

    private fun triggerGreenLight() {
        if (_state.value.isGreenLightOn) return

        val manifest = AllReportsManifest(
            totalExpected = _state.value.expectedBarrier,
            totalCompleted = collectedReports.size,
            successCount = collectedReports.count { it.status == BuilderStatus.SUCCESS.name },
            failedCount = collectedReports.count { it.status == BuilderStatus.FAILED.name },
            abortedCount = collectedReports.count { it.status == BuilderStatus.ABORTED_UPSTREAM_CORRUPTED.name },
            reports = collectedReports.toList(),
            isGreenLightTriggered = true,
            totalDurationMs = System.currentTimeMillis() - swarmStartTime,
            totalTokensBurned = collectedReports.sumOf { (it.promptTokens + it.candidateTokens).toLong() }
        )

        _state.update { it.copy(isGreenLightOn = true, statusMessage = "🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Все файлы на диске UFS 4.0.") }
        barrierDeferred.complete(manifest)
        coordinatorScope.launch {
            _events.emit(SwarmEvent.GreenLightIgnited(manifest))
        }
        AppLogger.i(AppLogger.TAG_APP, "SwarmCoordinator: 🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Готово ${manifest.totalCompleted} файлов.")
    }

    private suspend fun executeLiteUnaryWithRetry(
        systemPrompt: String,
        history: List<LiteContentDto>
    ): LiteUnaryResponse {
        var attempt = 0
        while (attempt < 3) {
            try {
                return executeSingleUnary(systemPrompt, history)
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw e
                val backoff = (500L * 2.0.pow(attempt.toDouble())).toLong() + Random.nextLong(50, 200)
                delay(backoff)
            }
        }
        throw IllegalStateException("Не удалось вызвать Gemini 3.5 Flash-Lite")
    }

    private suspend fun executeSingleUnary(
        systemPrompt: String,
        history: List<LiteContentDto>
    ): LiteUnaryResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        val url = "$API_BASE_URL/models/$LITE_MODEL_NAME:generateContent?key=$apiKey"

        val sandboxTools = listOf(
            GeminiToolDto(
                functionDeclarations = listOf(
                    FunctionDeclarationDto(
                        name = "sandbox_write_file",
                        description = "Записывает чистовой исходный код в файл рабочей области.",
                        parameters = FunctionParametersSchemaDto(
                            properties = mapOf(
                                "path" to ParameterPropertyDto(type = "STRING", description = "Путь к файлу"),
                                "content" to ParameterPropertyDto(type = "STRING", description = "Полный исходный код файла")
                            ),
                            required = listOf("path", "content")
                        )
                    ),
                    FunctionDeclarationDto(
                        name = "sandbox_submit_report",
                        description = "Сдает финальный отчет о выполненной задаче.",
                        parameters = FunctionParametersSchemaDto(
                            properties = mapOf(
                                "status" to ParameterPropertyDto(type = "STRING", description = "SUCCESS или FAILED", enum = listOf("SUCCESS", "FAILED")),
                                "summary" to ParameterPropertyDto(type = "STRING", description = "Краткое резюме проделанной работы")
                            ),
                            required = listOf("status", "summary")
                        )
                    )
                )
            )
        )

        val payload = LiteWireRequest(
            systemInstruction = LiteSystemInstructionDto(listOf(LitePartDto(text = systemPrompt))),
            contents = history,
            tools = sandboxTools,
            generationConfig = LiteGenerationConfigDto()
        )

        val response = httpClient.post(url) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LiteWireRequest.serializer(), payload))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Lite HTTP Error: ${response.status.value}: ${response.bodyAsText()}")
        }

        json.decodeFromString(LiteUnaryResponse.serializer(), response.bodyAsText())
    }

    fun getBurnedTokensSnapshot(): Long {
        return collectedReports.sumOf { (it.promptTokens + it.candidateTokens).toLong() }
    }

    override fun close() {
        coordinatorScope.cancel()
        if (shouldCloseHttpClient) {
            httpClient.close()
        }
    }
}