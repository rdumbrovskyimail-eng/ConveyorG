package com.conveyorg.agent

import android.content.Context
import com.conveyorg.data.LocalWorkspaceManager
import com.conveyorg.network.FunctionDeclarationDto
import com.conveyorg.network.FunctionParametersSchemaDto
import com.conveyorg.network.GeminiToolDto
import com.conveyorg.network.ParameterPropertyDto
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
    PRIMARY_A,        // Основной строитель (10 этапов x 10%, автономен)
    CROSS_CUTTING_B   // Сквозной строитель (ждет отчета предшественника по закону A -> B)
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
    @SerialName("functionCall") val functionCall: LiteFunctionCallDto? = null,
    @SerialName("functionResponse") val functionResponse: LiteFunctionResponseDto? = null
)

@Serializable
internal data class LiteFunctionCallDto(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap())
)

@Serializable
internal data class LiteFunctionResponseDto(
    val name: String,
    val response: JsonObject
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
        const val MAX_TOTAL_BUILDERS = 20
        const val MAX_PRIMARY_BUILDERS_A = 10
        const val MAX_CROSS_BUILDERS_B = 10
        private const val CONCURRENCY_PERMITS = 10 // Максимум 10 параллельных сетевых сокетов

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
                maxConnectionsCount = 32
                endpoint {
                    maxConnectionsPerRoute = 16
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

    private val _events = MutableSharedFlow<SwarmEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SwarmEvent> = _events.asSharedFlow()

    private val coordinatorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Реестры задач, сигналов эстафеты и собранных отчетов
    private val registeredTasks = ConcurrentHashMap<String, BuilderTask>()
    private val taskSignals = ConcurrentHashMap<String, CompletableDeferred<BuilderReport>>()
    private val collectedReports = CopyOnWriteArrayList<BuilderReport>()

    // Семафор управления квотами Google API
    private val concurrencySemaphore = Semaphore(CONCURRENCY_PERMITS)
    private val registrationMutex = Mutex()

    // Атомарный счетчик барьера
    private val atomicRemainingBarrier = AtomicInteger(0)
    private var barrierDeferred = CompletableDeferred<AllReportsManifest>()
    private val failedPrimaryTasksCount = AtomicInteger(0)

    private val swarmStartTime = System.currentTimeMillis()

    // ====================================================================
    // 4. Регистрация Задач (Соблюдение Квоты 10A + 10B = 20)
    // ====================================================================

    /**
     * Регистрация Основного Билдера A (Класс A: автономен, 10 этапов x 10%).
     */
    suspend fun registerPrimaryBuilderA(
        stageNumber: Int,
        targetFile: String,
        instruction: String,
        referenceCode: String? = null
    ): String = registrationMutex.withLock {
        val currentState = _state.value
        check(!currentState.isSealed) { "Барьер уже запечатан. Регистрация новых задач запрещена." }
        check(currentState.primaryCountA < MAX_PRIMARY_BUILDERS_A) {
            "Превышен лимит основных билдеров A (Максимум: $MAX_PRIMARY_BUILDERS_A)."
        }
        check(currentState.registeredCount < MAX_TOTAL_BUILDERS) {
            "Достигнут абсолютный потолок роя (Максимум: $MAX_TOTAL_BUILDERS клиентов)."
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
                statusMessage = "Зарегистрирован билдер A ($stageNumber/10): $targetFile"
            )
        }
        _events.emit(SwarmEvent.TaskRegistered(taskId, BuilderRole.PRIMARY_A, targetFile))

        // Запуск воркера в изолированной корутине
        coordinatorScope.launch {
            executeWorkerLifecycle(task, signal)
        }

        taskId
    }

    /**
     * Регистрация Сквозного Билдера B (Класс B: строго зависит от предшественника по закону A -> B).
     */
    suspend fun registerCrossCuttingBuilderB(
        dependencyTaskId: String,
        stageNumber: Int,
        targetFile: String,
        instruction: String
    ): String = registrationMutex.withLock {
        val currentState = _state.value
        check(!currentState.isSealed) { "Барьер уже запечатан. Регистрация новых задач запрещена." }
        check(currentState.crossCountB < MAX_CROSS_BUILDERS_B) {
            "Превышен лимит сквозных билдеров B (Максимум: $MAX_CROSS_BUILDERS_B)."
        }
        check(currentState.registeredCount < MAX_TOTAL_BUILDERS) {
            "Достигнут абсолютный потолок роя (Максимум: $MAX_TOTAL_BUILDERS клиентов)."
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

    // ====================================================================
    // 5. Запечатывание Барьера и Ожидание «Зеленой Лампочки»
    // ====================================================================

    /**
     * Директива Оркестратора на запечатывание барьера на N отчетов (N <= 20).
     */
    suspend fun sealBarrier(expectedCount: Int): Boolean = registrationMutex.withLock {
        check(expectedCount in 1..MAX_TOTAL_BUILDERS) {
            "Недопустимое значение барьера: $expectedCount (Лимит 1..$MAX_TOTAL_BUILDERS)"
        }

        val completedNow = collectedReports.size
        val remaining = maxOf(0, expectedCount - completedNow)
        atomicRemainingBarrier.set(remaining)

        _state.update {
            it.copy(
                isSealed = true,
                expectedBarrier = expectedCount,
                remainingBarrier = remaining,
                statusMessage = "Барьер запечатан на $expectedCount задач. Осталось отчетов: $remaining"
            )
        }

        AppLogger.i(AppLogger.TAG_APP, "SwarmCoordinator: Барьер запечатан (планка=$expectedCount, осталось=$remaining)")

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

    // ====================================================================
    // 6. Жизненный Цикл Воркера 3.5 Flash-Lite (Закон A -> B и Песочница)
    // ====================================================================

    private suspend fun executeWorkerLifecycle(
        task: BuilderTask,
        taskSignal: CompletableDeferred<BuilderReport>
    ) {
        val workerStartTime = System.currentTimeMillis()

        // 1. Соблюдение Закона A -> B: ожидание предшественника для задач Класса B
        if (task.role == BuilderRole.CROSS_CUTTING_B && task.dependencyTaskId != null) {
            _events.emit(SwarmEvent.TaskAwaitingDependency(task.taskId, task.dependencyTaskId))
            val parentSignal = taskSignals[task.dependencyTaskId]

            val parentReport = parentSignal?.await()
            if (parentReport == null || parentReport.status != BuilderStatus.SUCCESS.name) {
                // КАСКАДНЫЙ СБРОС: предшественник упал -> сквозной воркер аннулируется без запуска
                AppLogger.w(AppLogger.TAG_APP, "SwarmWorker [${task.taskId}]: Предшественник упал. Аннулирование по закону A -> B.")
                val abortReport = BuilderReport(
                    taskId = task.taskId,
                    role = task.role.name,
                    targetFile = task.targetFile,
                    status = BuilderStatus.ABORTED_UPSTREAM_CORRUPTED.name,
                    summary = "Задача аннулирована: родительская задача ${task.dependencyTaskId} завершилась со сбоем.",
                    filesTouched = emptyList(),
                    durationMs = System.currentTimeMillis() - workerStartTime,
                    errorMessage = "Upstream task failure"
                )
                finalizeTaskReport(abortReport, taskSignal)
                return
            }
        }

        // 2. Вход в сетевое окно через семафор
        concurrencySemaphore.withPermit {
            _state.update { it.copy(activeWorkersCount = it.activeWorkersCount + 1) }
            _events.emit(SwarmEvent.TaskStarted(task.taskId, task.role))

            try {
                withTimeout(WORKER_TIMEOUT_MS) {
                    val report = runWorkerReActLoop(task, workerStartTime)
                    finalizeTaskReport(report, taskSignal)
                }
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_APP, "SwarmWorker [${task.taskId}]: Фатальная ошибка воркера: ${e.message}", e)
                val failReport = BuilderReport(
                    taskId = task.taskId,
                    role = task.role.name,
                    targetFile = task.targetFile,
                    status = BuilderStatus.FAILED.name,
                    summary = "Сбой выполнения воркера: ${e.localizedMessage}",
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

    /**
     * Быстрый ReAct цикл воркера 3.5 Flash-Lite в изолированной песочнице (максимум 5 шагов).
     */
    private suspend fun runWorkerReActLoop(task: BuilderTask, startTime: Long): BuilderReport {
        val systemPrompt = "Ты — быстрый инженер-сборщик ClientG на базе Gemini 3.5 Flash-Lite.\n" +
                "Твоя цель: реализовать подзадачу строго для файла '${task.targetFile}'.\n" +
                "ПЕСОЧНИЦА ИНСТРУМЕНТОВ:\n" +
                "1. sandbox_read_file(path) — прочесть файл.\n" +
                "2. sandbox_write_file(path, content) — записать чистовой код без Markdown-разметки.\n" +
                "3. sandbox_submit_report(status, summary) — сдать отчет о завершении.\n" +
                "Запрещено писать код в чат — записывай его строго через 'sandbox_write_file'."

        val initialPrompt = buildString {
            appendLine("ЗАДАЧА [${task.role} | Этап ${task.stageNumber}]:")
            appendLine("Целевой файл: ${task.targetFile}")
            appendLine("Инструкция:\n${task.instruction}")
            if (!task.referenceCode.isNullOrBlank()) {
                appendLine("\nЭТАЛОННЫЙ АРХИТЕКТУРНЫЙ КОД:\n${task.referenceCode}")
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
            val functionCall = candidate.content?.parts?.firstOrNull { it.functionCall != null }?.functionCall

            if (functionCall != null) {
                conversation.add(LiteContentDto(role = "model", parts = listOf(LitePartDto(functionCall = functionCall))))

                // Исполнение в локальной песочнице
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
                        val content = functionCall.args["content"]?.jsonPrimitive?.content ?: ""

                        // Очистка от случайных Markdown-тегов ```kotlin
                        val cleanContent = content.removePrefix("```kotlin")
                            .removePrefix("```")
                            .removeSuffix("```")
                            .trim()

                        // Запись через атомарный менеджер с пофайловым мьютексом
                        workspaceManager.writeFileAtomic(path, cleanContent.toByteArray(Charsets.UTF_8))
                        if (path !in touchedFiles) touchedFiles.add(path)

                        buildJsonObject {
                            put("status", "success")
                            put("written_bytes", cleanContent.length)
                        }
                    }
                    "sandbox_submit_report" -> {
                        val statusStr = functionCall.args["status"]?.jsonPrimitive?.content ?: "SUCCESS"
                        val summaryStr = functionCall.args["summary"]?.jsonPrimitive?.content ?: "Код успешно интегрирован."

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
                    else -> buildJsonObject { put("error", "Неизвестный инструмент песочницы.") }
                }

                conversation.add(
                    LiteContentDto(
                        role = "tool",
                        parts = listOf(LitePartDto(functionResponse = LiteFunctionResponseDto(functionCall.name, toolResult)))
                    )
                )
            } else {
                // Модель закончила без явного вызова submit_report
                break
            }
        }

        return reportOutcome ?: BuilderReport(
            taskId = task.taskId,
            role = task.role.name,
            targetFile = task.targetFile,
            status = if (touchedFiles.isNotEmpty()) BuilderStatus.SUCCESS.name else BuilderStatus.FAILED.name,
            summary = "Работа завершена воркером (затронуто файлов: ${touchedFiles.size}).",
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

        // Проверка предохранителя роя (Swarm Circuit Breaker)
        if (failedPrimaryTasksCount.get() >= 3) {
            _state.update { it.copy(circuitBreakerTripped = true) }
            _events.emit(SwarmEvent.CircuitBreakerTripped("Слишком много сбоев основных задач A (>= 3). Волна прервана."))
        }

        val remaining = atomicRemainingBarrier.decrementAndGet()
        _state.update {
            it.copy(
                remainingBarrier = maxOf(0, remaining),
                reportsSubmittedCount = it.reportsSubmittedCount + 1,
                statusMessage = "Сдан отчет [${report.taskId}]: ${report.status} (Осталось: ${maxOf(0, remaining)})"
            )
        }
        _events.emit(SwarmEvent.ReportSubmitted(report.taskId, BuilderStatus.valueOf(report.status), maxOf(0, remaining)))

        // Проверка достижения «Зеленой лампочки»
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

        _state.update { it.copy(isGreenLightOn = true, statusMessage = "🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Все отчеты собраны.") }
        barrierDeferred.complete(manifest)
        coordinatorScope.launch {
            _events.emit(SwarmEvent.GreenLightIgnited(manifest))
        }
        AppLogger.i(AppLogger.TAG_APP, "SwarmCoordinator: 🟢 ЗЕЛЕНАЯ ЛАМПОЧКА ВСПЫХНУЛА! Манифест готов (${manifest.totalCompleted} отчетов).")
    }

    // ====================================================================
    // 7. Сверхбыстрый Unary REST Вызов к Gemini 3.5 Flash-Lite
    // ====================================================================

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
                        name = "sandbox_read_file",
                        description = "Читает файл из песочницы.",
                        parameters = FunctionParametersSchemaDto(
                            properties = mapOf("path" to ParameterPropertyDto(type = "STRING", description = "Путь к файлу"))
                        )
                    ),
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
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LiteWireRequest.serializer(), payload))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Lite HTTP Error: ${response.status.value}: ${response.bodyAsText()}")
        }

        json.decodeFromString(LiteUnaryResponse.serializer(), response.bodyAsText())
    }

    override fun close() {
        coordinatorScope.cancel()
        if (shouldCloseHttpClient) {
            httpClient.close()
        }
    }
}