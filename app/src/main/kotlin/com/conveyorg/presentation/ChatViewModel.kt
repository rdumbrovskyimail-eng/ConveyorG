package com.conveyorg.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.conveyorg.data.AtomicSessionStore
import com.conveyorg.data.ChatSessionMetadata
import com.conveyorg.network.*
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.UUID

@Serializable
data class UiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String = "",
    val attachments: List<TextAttachment> = emptyList(),
    val thoughtSignature: String? = null,
    val thoughtText: String = "",
    val thinkingDurationMs: Long = 0L,
    val isThinkingActive: Boolean = false,
    val isThinkingExpanded: Boolean = false,
    val hasThoughts: Boolean = false,
    val searchQueries: List<String> = emptyList(),
    val sources: List<GroundingSource> = emptyList(),
    val citations: List<InlineCitation> = emptyList(),
    val searchSuggestionsHtml: String? = null,
    val usage: GeminiStreamEvent.UsageReported? = null,
    val finishReason: FinishReason? = null,
    val isStreaming: Boolean = false
)

data class ChatUiState(
    val messages: List<UiChatMessage> = emptyList(),
    val inputText: String = "",
    val attachedFiles: List<TextAttachment> = emptyList(),
    val isGenerating: Boolean = false,
    val enableSearch: Boolean = false,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val apiKey: String = "",
    val isApiKeyDialogOpen: Boolean = false,
    val errorMessage: String? = null,
    val retryCountdownSeconds: Long? = null,
    // --- Постоянное хранилище сессий ---
    val currentSessionId: String = UUID.randomUUID().toString(),
    val sessionList: List<ChatSessionMetadata> = emptyList(),
    // --- Аппаратная защита кэша Google TPU ---
    val pinnedCacheId: String? = null,
    val isPinningCache: Boolean = false
)

sealed interface ChatUiSideEffect {
    data object ScrollToBottom : ChatUiSideEffect
    data object HapticLightTick : ChatUiSideEffect
    data object HapticThinkingCompleted : ChatUiSideEffect
    data object HapticGenerationFinished : ChatUiSideEffect
    data class ShowToast(val message: String) : ChatUiSideEffect
}

class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle? = null,
    private val externalClient: GeminiClient? = null
) : AndroidViewModel(application) {

    private val sessionStore = AtomicSessionStore(application)
    private val initialInputText: String = savedStateHandle?.get<String>(KEY_INPUT_DRAFT) ?: ""

    private val _uiState = MutableStateFlow(ChatUiState(inputText = initialInputText))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _uiEffects = Channel<ChatUiSideEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEffects: Flow<ChatUiSideEffect> = _uiEffects.receiveAsFlow()

    private val activeClient: GeminiClient
    private val shouldCloseClientOnExit: Boolean

    private var generationJob: Job? = null
    private var countdownJob: Job? = null
    private var activeTypewriter: TypewriterEngine? = null

    @Volatile
    private var securePrefs: SharedPreferences? = null

    @Volatile
    private var cachedApiKey: String = ""

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSafeSharedPreferences(context).also { securePrefs = it }
        }
    }

    init {
        AppLogger.i(AppLogger.TAG_VM, "ChatViewModel: Инициализация. Чтение ключа Knox Vault и загрузка сессий...")

        // 1. Чтение ключа из аппаратного хранилища Knox Vault
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getSecurePrefs(getApplication())
            val savedKey = prefs.getString(PREF_KEY_API_KEY, "") ?: ""
            cachedApiKey = savedKey
            _uiState.update { it.copy(apiKey = savedKey) }
            AppLogger.d(AppLogger.TAG_VM, "ChatViewModel: Ключ прочитан: ${if (savedKey.isNotBlank()) AppLogger.maskKey(savedKey) else "ПУСТО"}")
        }

        // 2. Асинхронное восстановление последней сессии из AtomicFile
        viewModelScope.launch(Dispatchers.IO) {
            val index = sessionStore.loadIndex()
            val activeId = index.activeSessionId ?: UUID.randomUUID().toString()
            val restoredSessionData = sessionStore.loadSession(activeId)

            _uiState.update {
                it.copy(
                    currentSessionId = activeId,
                    sessionList = index.sessions,
                    messages = restoredSessionData?.messages ?: emptyList(),
                    pinnedCacheId = restoredSessionData?.metadata?.pinnedCacheId
                )
            }

            if (!restoredSessionData?.messages.isNullOrEmpty()) {
                _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                AppLogger.i(AppLogger.TAG_VM, "ChatViewModel: Сессия $activeId восстановлена (${restoredSessionData?.messages?.size} сообщ.)")
            }
        }

        if (externalClient != null) {
            activeClient = externalClient
            shouldCloseClientOnExit = false
        } else {
            activeClient = GeminiClient(
                apiKeyProvider = { cachedApiKey.ifBlank { _uiState.value.apiKey } }
            )
            shouldCloseClientOnExit = true
        }
    }

    // ====================================================================
    // Управление постоянными сессиями (Session Management)
    // ====================================================================

    fun onNewSession() {
        if (_uiState.value.isGenerating) onCancelGeneration()
        val newId = UUID.randomUUID().toString()
        AppLogger.i(AppLogger.TAG_VM, "onNewSession: Создание нового диалога $newId")
        _uiState.update {
            it.copy(
                currentSessionId = newId,
                messages = emptyList(),
                attachedFiles = emptyList(),
                pinnedCacheId = null,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }
    }

    fun onSelectSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return
        if (_uiState.value.isGenerating) onCancelGeneration()

        AppLogger.i(AppLogger.TAG_VM, "onSelectSession: Переключение на сессию $sessionId")
        viewModelScope.launch(Dispatchers.IO) {
            val sessionData = sessionStore.loadSession(sessionId)
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = sessionData?.messages ?: emptyList(),
                    attachedFiles = emptyList(),
                    pinnedCacheId = sessionData?.metadata?.pinnedCacheId,
                    errorMessage = null,
                    retryCountdownSeconds = null
                )
            }
            _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
        }
    }

    fun onDeleteSession(sessionId: String) {
        AppLogger.i(AppLogger.TAG_VM, "onDeleteSession: Удаление сессии $sessionId")
        val state = _uiState.value
        val targetMetadata = state.sessionList.find { it.id == sessionId }
        val cacheToDelete = targetMetadata?.pinnedCacheId

        viewModelScope.launch(Dispatchers.IO) {
            // Удаляем явный кэш из серверов Google
            if (!cacheToDelete.isNullOrBlank()) {
                activeClient.deleteExplicitCache(cacheToDelete)
            }

            val updatedIndex = sessionStore.removeSession(sessionId)
            val nextActiveId = updatedIndex.activeSessionId ?: UUID.randomUUID().toString()
            val nextSessionData = if (updatedIndex.activeSessionId != null) {
                sessionStore.loadSession(nextActiveId)
            } else {
                null
            }

            _uiState.update {
                it.copy(
                    sessionList = updatedIndex.sessions,
                    currentSessionId = nextActiveId,
                    messages = nextSessionData?.messages ?: emptyList(),
                    pinnedCacheId = nextSessionData?.metadata?.pinnedCacheId
                )
            }
        }
    }

    private fun scheduleSessionPersistence() {
        val snapshot = _uiState.value
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val updatedIndex = sessionStore.persistSession(
                sessionId = snapshot.currentSessionId,
                messages = snapshot.messages,
                pinnedCacheId = snapshot.pinnedCacheId
            )
            _uiState.update { it.copy(sessionList = updatedIndex.sessions) }
        }
    }

    // ====================================================================
    // Базовые события экрана и настроек
    // ====================================================================

    fun onInputTextChanged(newText: String) {
        savedStateHandle?.set(KEY_INPUT_DRAFT, newText)
        _uiState.update { it.copy(inputText = newText) }
    }

    fun onToggleSearch(enabled: Boolean) {
        AppLogger.i(AppLogger.TAG_VM, "onToggleSearch: $enabled")
        _uiState.update { it.copy(enableSearch = enabled) }
    }

    fun onThinkingLevelChanged(level: ThinkingLevel) {
        AppLogger.i(AppLogger.TAG_VM, "onThinkingLevelChanged: $level")
        _uiState.update { it.copy(thinkingLevel = level) }
    }

    fun onOpenApiKeyDialog() {
        _uiState.update { it.copy(isApiKeyDialogOpen = true) }
    }

    fun onCloseApiKeyDialog() {
        _uiState.update { it.copy(isApiKeyDialogOpen = false) }
    }

    fun onSaveApiKey(newKey: String) {
        val trimmed = newKey.trim()
        cachedApiKey = trimmed
        AppLogger.i(AppLogger.TAG_VM, "onSaveApiKey: Сохранение ключа ${AppLogger.maskKey(trimmed)} в Knox Vault...")
        viewModelScope.launch(Dispatchers.IO) {
            getSecurePrefs(getApplication()).edit().putString(PREF_KEY_API_KEY, trimmed).apply()
            _uiState.update {
                it.copy(apiKey = trimmed, isApiKeyDialogOpen = false, errorMessage = null)
            }
            _uiEffects.trySend(ChatUiSideEffect.ShowToast("API-ключ сохранен в защищенном хранилище"))
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun onToggleThinkingAccordion(messageId: String) {
        _uiState.update { state ->
            val messages = state.messages
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val newMessages = ArrayList(messages)
                val target = newMessages[idx]
                newMessages[idx] = target.copy(isThinkingExpanded = !target.isThinkingExpanded)
                state.copy(messages = newMessages)
            } else {
                state
            }
        }
    }

    fun onClearChat() {
        AppLogger.i(AppLogger.TAG_VM, "onClearChat: Очистка текущего диалога")
        onCancelGeneration()
        val cacheToDelete = _uiState.value.pinnedCacheId

        _uiState.update {
            it.copy(
                messages = emptyList(),
                attachedFiles = emptyList(),
                pinnedCacheId = null,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (!cacheToDelete.isNullOrBlank()) {
                activeClient.deleteExplicitCache(cacheToDelete)
            }
            scheduleSessionPersistence()
        }
    }

    // ====================================================================
    // Работа с файлами и вложениями
    // ====================================================================

    fun onAttachFileUri(uri: Uri) {
        AppLogger.i(AppLogger.TAG_VM, "onAttachFileUri: Получен URI: $uri")
        viewModelScope.launch(Dispatchers.IO) {
            val signal = CancellationSignal()
            val job = coroutineContext.job
            val handle = job.invokeOnCompletion { signal.cancel() }

            try {
                val context = getApplication<Application>()
                val resolver = context.contentResolver

                var fileName = "attachment.txt"
                var fileSize = 0L

                resolver.query(uri, null, null, null, null, signal)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                            fileName = cursor.getString(nameIdx) ?: fileName
                        }
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }

                val maxBytes = TextAttachment.MAX_ATTACHMENT_CHARS.toLong() * 4L
                if (fileSize > 0L && fileSize > maxBytes) {
                    val sizeMb = fileSize / (1024 * 1024)
                    val limitMb = maxBytes / (1024 * 1024)
                    throw IllegalArgumentException("Файл '$fileName' ($sizeMb МБ) превышает лимит ($limitMb МБ).")
                }

                val content = resolver.openInputStream(uri)?.use { stream ->
                    readStreamSafely(stream, TextAttachment.MAX_ATTACHMENT_CHARS)
                } ?: throw IllegalArgumentException("Не удалось открыть поток чтения файла.")

                val newAttachment = TextAttachment(fileName = fileName, content = content)

                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        val uniqueAttachment = makeUniqueAttachment(current.attachedFiles, newAttachment)
                        if (uniqueAttachment != null) {
                            current.copy(attachedFiles = current.attachedFiles + uniqueAttachment)
                        } else {
                            current
                        }
                    }
                    _uiEffects.trySend(ChatUiSideEffect.HapticLightTick)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(AppLogger.TAG_VM, "onAttachFileUri: Ошибка прикрепления файла", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Ошибка вложения: ${e.localizedMessage}") }
                }
            } finally {
                handle.dispose()
            }
        }
    }

    fun onRemoveAttachment(attachment: TextAttachment) {
        _uiState.update { it.copy(attachedFiles = it.attachedFiles - attachment) }
    }

    private fun makeUniqueAttachment(existing: List<TextAttachment>, attachment: TextAttachment): TextAttachment? {
        if (existing.any { it.fileName == attachment.fileName && it.content == attachment.content }) return null
        if (existing.none { it.fileName == attachment.fileName }) return attachment

        val baseName = attachment.fileName.substringBeforeLast('.', attachment.fileName)
        val ext = if (attachment.fileName.contains('.')) ".${attachment.fileName.substringAfterLast('.')}" else ""
        var counter = 1
        var candidateName: String
        do {
            candidateName = "$baseName ($counter)$ext"
            counter++
        } while (existing.any { it.fileName == candidateName })

        return TextAttachment(fileName = candidateName, content = attachment.content)
    }

    private fun readStreamSafely(stream: InputStream, maxChars: Int): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(8192)
        val builder = StringBuilder()
        var totalCharsRead = 0

        while (true) {
            val read = reader.read(buffer)
            if (read == -1) break
            totalCharsRead += read
            if (totalCharsRead > maxChars) {
                throw IllegalArgumentException("Текстовый файл превышает лимит ($maxChars символов).")
            }
            builder.append(buffer, 0, read)
        }

        var result = builder.toString()
        if (result.startsWith("\uFEFF")) {
            result = result.removePrefix("\uFEFF")
        }
        return result
    }

    // ====================================================================
    // Отправка сообщений и фиксация кэша Google TPU
    // ====================================================================

    fun onSendMessage() {
        val state = _uiState.value
        val prompt = state.inputText.trim()
        val attachments = state.attachedFiles

        if (prompt.isBlank() && attachments.isEmpty()) return
        if (state.isGenerating || state.isPinningCache) return

        if (state.apiKey.isBlank() && cachedApiKey.isBlank()) {
            _uiState.update {
                it.copy(isApiKeyDialogOpen = true, errorMessage = "Для отправки запроса укажите Gemini API Key.")
            }
            return
        }

        countdownJob?.cancel()

        viewModelScope.launch(Dispatchers.IO) {
            var currentCacheId = state.pinnedCacheId

            // АВТО-ЗАЩИТА ТОКЕНОВ: Если файл > 100 КБ (~30k токенов) и кэш еще не создан
            val totalAttachmentChars = attachments.sumOf { it.content.length }
            if (currentCacheId == null && totalAttachmentChars > 120_000) {
                try {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isPinningCache = true) }
                        _uiEffects.trySend(ChatUiSideEffect.ShowToast("Фиксация 140к токенов в кэше Google TPU..."))
                    }

                    // Закрепляем в памяти TPU на 2 часа
                    currentCacheId = activeClient.pinExplicitContextCache(attachments)

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(pinnedCacheId = currentCacheId, isPinningCache = false) }
                        _uiEffects.trySend(ChatUiSideEffect.ShowToast("Кэш токенов зафиксирован (скидка 90%)"))
                    }
                } catch (e: Exception) {
                    AppLogger.e(AppLogger.TAG_VM, "Не удалось зафиксировать явный кэш, отправка по обычному каналу", e)
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isPinningCache = false) }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                startStreamTurn(prompt, attachments, currentCacheId)
            }
        }
    }

    private fun startStreamTurn(prompt: String, attachments: List<TextAttachment>, cacheId: String?) {
        val state = _uiState.value
        val userMessage = UiChatMessage(
            role = ChatRole.USER,
            text = prompt,
            attachments = attachments
        )

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = UiChatMessage(
            id = assistantMessageId,
            role = ChatRole.MODEL,
            isStreaming = true,
            isThinkingActive = true,
            isThinkingExpanded = true,
            hasThoughts = true
        )

        val sanitizedHistory = state.messages.mapNotNull { msg ->
            if (msg.role == ChatRole.MODEL && msg.text.isBlank()) null
            else if (msg.text.isBlank() && msg.attachments.isEmpty()) null
            else ChatMessage(
                role = msg.role,
                text = msg.text,
                attachments = msg.attachments,
                thoughtSignature = msg.thoughtSignature
            )
        }

        savedStateHandle?.set(KEY_INPUT_DRAFT, "")
        _uiState.update { current ->
            current.copy(
                messages = current.messages + userMessage + initialAssistantMessage,
                inputText = "",
                attachedFiles = emptyList(),
                isGenerating = true,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        // Атомарно сохраняем историю сразу после хода пользователя
        scheduleSessionPersistence()

        executeStream(
            assistantMessageId = assistantMessageId,
            prompt = prompt,
            history = sanitizedHistory,
            attachments = attachments,
            cachedContentId = cacheId,
            thinkingLevel = state.thinkingLevel,
            enableSearch = state.enableSearch
        )
    }

    fun onRetryLastMessage() {
        onRetryMessage(targetMessageId = null)
    }

    fun onRetryMessage(targetMessageId: String? = null) {
        val state = _uiState.value
        if (state.isGenerating || state.isPinningCache) return

        val targetModelIndex = if (targetMessageId != null) {
            state.messages.indexOfLast { it.id == targetMessageId }
        } else {
            state.messages.indexOfLast { it.role == ChatRole.MODEL }
        }

        val searchList = if (targetModelIndex != -1) state.messages.take(targetModelIndex + 1) else state.messages
        val targetUserMessage = searchList.lastOrNull { it.role == ChatRole.USER } ?: return
        val targetUserIndex = state.messages.indexOfLast { it.id == targetUserMessage.id }
        if (targetUserIndex == -1) return

        val messagesHistory = state.messages.take(targetUserIndex)

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = UiChatMessage(
            id = assistantMessageId,
            role = ChatRole.MODEL,
            isStreaming = true,
            isThinkingActive = true,
            isThinkingExpanded = true,
            hasThoughts = true
        )

        val sanitizedHistory = messagesHistory.mapNotNull { msg ->
            if (msg.role == ChatRole.MODEL && msg.text.isBlank()) null
            else if (msg.text.isBlank() && msg.attachments.isEmpty()) null
            else ChatMessage(
                role = msg.role,
                text = msg.text,
                attachments = msg.attachments,
                thoughtSignature = msg.thoughtSignature
            )
        }

        _uiState.update { current ->
            current.copy(
                messages = messagesHistory + targetUserMessage + initialAssistantMessage,
                isGenerating = true,
                errorMessage = null,
                retryCountdownSeconds = null
            )
        }

        scheduleSessionPersistence()

        executeStream(
            assistantMessageId = assistantMessageId,
            prompt = targetUserMessage.text,
            history = sanitizedHistory,
            attachments = targetUserMessage.attachments,
            cachedContentId = state.pinnedCacheId,
            thinkingLevel = state.thinkingLevel,
            enableSearch = state.enableSearch
        )
    }

    private fun executeStream(
        assistantMessageId: String,
        prompt: String,
        history: List<ChatMessage>,
        attachments: List<TextAttachment>,
        cachedContentId: String?,
        thinkingLevel: ThinkingLevel,
        enableSearch: Boolean
    ) {
        _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
        _uiEffects.trySend(ChatUiSideEffect.HapticLightTick)

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val typewriter = TypewriterEngine(
                onFrame = { thoughtDelta, contentDelta ->
                    if (thoughtDelta.isEmpty() && contentDelta.isEmpty()) return@TypewriterEngine
                    _uiState.update { currentState ->
                        val messages = currentState.messages
                        val idx = messages.indexOfLast { it.id == assistantMessageId }
                        if (idx == -1) return@update currentState

                        val target = messages[idx]
                        val updated = target.copy(
                            thoughtText = if (thoughtDelta.isNotEmpty()) target.thoughtText + thoughtDelta else target.thoughtText,
                            text = if (contentDelta.isNotEmpty()) target.text + contentDelta else target.text,
                            hasThoughts = target.hasThoughts || thoughtDelta.isNotEmpty() || target.thoughtText.isNotEmpty()
                        )
                        val newMessages = ArrayList(messages)
                        newMessages[idx] = updated
                        currentState.copy(messages = newMessages)
                    }
                },
                onComplete = {
                    _uiState.update { currentState ->
                        val messages = currentState.messages
                        val idx = messages.indexOfLast { it.id == assistantMessageId }
                        if (idx == -1) {
                            currentState.copy(isGenerating = false)
                        } else {
                            val target = messages[idx]
                            val updated = target.copy(isStreaming = false, isThinkingActive = false)
                            val newMessages = ArrayList(messages)
                            newMessages[idx] = updated
                            currentState.copy(messages = newMessages, isGenerating = false)
                        }
                    }
                    _uiEffects.trySend(ChatUiSideEffect.HapticGenerationFinished)
                    _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)

                    // Атомарно сохраняем сессию на диск после полной печати текста
                    scheduleSessionPersistence()
                }
            )
            activeTypewriter = typewriter
            typewriter.start(this)

            try {
                activeClient.streamContent(
                    prompt = prompt,
                    history = history,
                    attachments = attachments,
                    cachedContentId = cachedContentId,
                    onCacheExpired = {
                        // САМОИСЦЕЛЕНИЕ: Если кэш в Google истек через 2 часа, перевыпускаем его на лету
                        AppLogger.w(AppLogger.TAG_VM, "onCacheExpired: Перевыпуск истекшего кэша в Google...")
                        val allSessionAttachments = _uiState.value.messages.flatMap { it.attachments } + attachments
                        if (allSessionAttachments.isNotEmpty()) {
                            val newId = activeClient.pinExplicitContextCache(allSessionAttachments)
                            _uiState.update { it.copy(pinnedCacheId = newId) }
                            scheduleSessionPersistence()
                            newId
                        } else null
                    },
                    thinkingLevel = thinkingLevel,
                    enableSearch = enableSearch
                ).collect { event ->
                    when (event) {
                        is GeminiStreamEvent.ThinkingStarted -> {
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }
                        is GeminiStreamEvent.ThinkingDelta -> typewriter.enqueueThought(event.text)
                        is GeminiStreamEvent.ThinkingCompleted -> {
                            typewriter.markThinkingEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.HapticThinkingCompleted)
                            }
                        }
                        is GeminiStreamEvent.ContentStarted -> {
                            typewriter.markThinkingEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                                _uiEffects.trySend(ChatUiSideEffect.ScrollToBottom)
                            }
                        }
                        is GeminiStreamEvent.ContentDelta -> typewriter.enqueueContent(event.text)
                        is GeminiStreamEvent.Completed -> {
                            typewriter.markStreamEnded()
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }
                        else -> {
                            withContext(Dispatchers.Main.immediate) {
                                handleDiscreteEvent(assistantMessageId, event)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        finalizeAssistantMessage(assistantMessageId, FinishReason.STOP)
                    }
                    scheduleSessionPersistence()
                }
            } catch (e: GeminiApiException) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        handleApiError(assistantMessageId, e)
                    }
                    scheduleSessionPersistence()
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    typewriter.stopAndFlush()
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update {
                            it.copy(isGenerating = false, errorMessage = "Непредвиденная ошибка: ${e.localizedMessage}")
                        }
                        finalizeAssistantMessage(assistantMessageId, FinishReason.UNKNOWN)
                    }
                    scheduleSessionPersistence()
                }
            }
        }
    }

    fun onCancelGeneration() {
        activeTypewriter?.stopAndFlush()
        activeTypewriter = null
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isGenerating = false) }
        scheduleSessionPersistence()
    }

    private fun handleDiscreteEvent(assistantMessageId: String, event: GeminiStreamEvent) {
        _uiState.update { currentState ->
            val messages = currentState.messages
            val idx = messages.indexOfLast { it.id == assistantMessageId }
            if (idx == -1) return@update currentState

            val currentMsg = messages[idx]

            val updatedMessage = when (event) {
                is GeminiStreamEvent.Connecting -> currentMsg.copy(isStreaming = true)
                is GeminiStreamEvent.ThinkingStarted -> currentMsg.copy(
                    isThinkingActive = true,
                    isThinkingExpanded = true,
                    hasThoughts = true
                )
                is GeminiStreamEvent.ThinkingCompleted -> currentMsg.copy(
                    isThinkingActive = false,
                    hasThoughts = true,
                    thinkingDurationMs = currentMsg.thinkingDurationMs + event.durationMs
                )
                is GeminiStreamEvent.ContentStarted -> currentMsg.copy(
                    isThinkingActive = false,
                    isThinkingExpanded = false
                )
                is GeminiStreamEvent.SearchQueriesDiscovered -> currentMsg.copy(
                    searchQueries = (currentMsg.searchQueries + event.queries).distinct()
                )
                is GeminiStreamEvent.SourcesDiscovered -> currentMsg.copy(
                    sources = (currentMsg.sources + event.newSources).distinctBy { it.url }
                )
                is GeminiStreamEvent.CitationsDiscovered -> currentMsg.copy(
                    citations = (currentMsg.citations + event.citations).distinct()
                )
                is GeminiStreamEvent.SearchEntryPointRendered -> currentMsg.copy(
                    searchSuggestionsHtml = event.htmlContent
                )
                is GeminiStreamEvent.UsageReported -> currentMsg.copy(usage = event)
                is GeminiStreamEvent.Completed -> currentMsg.copy(
                    finishReason = event.finishReason,
                    thoughtSignature = event.thoughtSignature ?: currentMsg.thoughtSignature
                )
                else -> currentMsg
            }

            val newMessages = ArrayList(messages)
            newMessages[idx] = updatedMessage
            currentState.copy(messages = newMessages)
        }
    }

    private fun finalizeAssistantMessage(assistantMessageId: String, reason: FinishReason) {
        _uiState.update { state ->
            val filteredMessages = state.messages.filterNot { msg ->
                msg.id == assistantMessageId && msg.text.isBlank() && msg.thoughtText.isBlank()
            }
            state.copy(
                isGenerating = false,
                messages = filteredMessages.map { msg ->
                    if (msg.id == assistantMessageId) {
                        msg.copy(isStreaming = false, isThinkingActive = false, finishReason = reason)
                    } else {
                        msg
                    }
                }
            )
        }
    }

    private fun handleApiError(assistantMessageId: String, error: GeminiApiException) {
        _uiState.update { state ->
            val filteredMessages = state.messages.filterNot { msg ->
                msg.id == assistantMessageId && msg.text.isBlank() && msg.thoughtText.isBlank()
            }
            val finalizedMessages = filteredMessages.map { msg ->
                if (msg.id == assistantMessageId) {
                    msg.copy(isStreaming = false, isThinkingActive = false, finishReason = FinishReason.UNKNOWN)
                } else {
                    msg
                }
            }
            state.copy(
                isGenerating = false,
                errorMessage = error.userFriendlyMessage,
                retryCountdownSeconds = error.retryAfterSeconds,
                messages = finalizedMessages
            )
        }
        error.retryAfterSeconds?.let { seconds ->
            startRetryCountdown(seconds)
        }
    }

    private fun startRetryCountdown(totalSeconds: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && isActive) {
                delay(1000)
                remaining--
                _uiState.update { it.copy(retryCountdownSeconds = remaining) }
            }
            _uiState.update { it.copy(retryCountdownSeconds = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeTypewriter?.stopAndFlush()
        activeTypewriter = null
        generationJob?.cancel()
        countdownJob?.cancel()
        if (shouldCloseClientOnExit) {
            activeClient.close()
        }
    }

    companion object {
        private const val PREF_KEY_API_KEY = "gemini_api_key"
        private const val KEY_INPUT_DRAFT = "key_input_draft_text"

        private fun createSafeSharedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "clientg_secure_settings",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                AppLogger.w(AppLogger.TAG_VM, "Knox Vault недоступен, откат к fallback SharedPreferences: ${t.message}")
                context.getSharedPreferences("clientg_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }
}