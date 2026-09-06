package com.conveyorg.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.conveyorg.presentation.UiChatMessage
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalSerializationApi::class)
class AtomicSessionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val ioMutex = Mutex()

    // Изоляция от Google Cloud Backup в noBackupFilesDir для защиты приватных данных и квот
    private val sessionsDirectory: File by lazy {
        File(context.noBackupFilesDir, "sessions_store").apply {
            if (!exists()) mkdirs()
        }
    }

    private val indexAtomicFile: AtomicFile by lazy {
        AtomicFile(File(sessionsDirectory, "sessions_index.json"))
    }

    /**
     * Асинхронное чтение индекса сессий при запуске приложения.
     */
    suspend fun loadIndex(): SessionsIndex = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val baseFile = indexAtomicFile.baseFile
            if (!baseFile.exists() || baseFile.length() == 0L) {
                return@withContext SessionsIndex()
            }

            try {
                BufferedInputStream(indexAtomicFile.openRead()).use { stream ->
                    json.decodeFromStream<SessionsIndex>(stream)
                }
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "AtomicSessionStore: Сбой чтения индекса, сброс к пустому", e)
                SessionsIndex()
            }
        }
    }

    /**
     * Загрузка полного снимка конкретной сессии (сообщения + привязанный ID кэша).
     * Автоматически санитизирует незавершенные состояния генерации при внезапном закрытии процесса.
     */
    suspend fun loadSession(sessionId: String): ChatSessionData? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val sessionFile = File(sessionsDirectory, "session_$sessionId.json")
            val atomic = AtomicFile(sessionFile)
            if (!atomic.baseFile.exists() || atomic.baseFile.length() == 0L) {
                return@withContext null
            }

            try {
                BufferedInputStream(atomic.openRead()).use { stream ->
                    val data = json.decodeFromStream<ChatSessionData>(stream)
                    val sanitizedMessages = data.messages.map { msg ->
                        if (msg.isStreaming || msg.isThinkingActive) {
                            msg.copy(
                                isStreaming = false,
                                isThinkingActive = false,
                                isThinkingExpanded = false
                            )
                        } else {
                            msg
                        }
                    }
                    data.copy(messages = sanitizedMessages)
                }
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "AtomicSessionStore: Сбой чтения сессии $sessionId", e)
                null
            }
        }
    }

    /**
     * Атомарная фиксация сессии на физическом диске.
     * Защищена от отмены корутины через NonCancellable.
     */
    suspend fun persistSession(
        sessionId: String,
        messages: List<UiChatMessage>,
        pinnedCacheId: String? = null,
        customTitle: String? = null
    ): SessionsIndex = withContext(Dispatchers.IO + NonCancellable) {
        ioMutex.withLock {
            var currentIndex = loadIndexInternal()

            // Автоматическое определение названия по первому запросу пользователя
            val firstPrompt = messages.firstOrNull { it.role.name == "USER" && it.text.isNotBlank() }?.text
            val resolvedTitle = customTitle
                ?: currentIndex.sessions.find { it.id == sessionId }?.title?.takeIf { it != "Новый диалог" }
                ?: firstPrompt?.take(36)?.trim()
                ?: "Новый диалог"

            val lastText = messages.lastOrNull()?.text?.take(60)?.replace("\n", " ") ?: ""

            val metadata = ChatSessionMetadata(
                id = sessionId,
                title = resolvedTitle,
                createdAt = currentIndex.sessions.find { it.id == sessionId }?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                messageCount = messages.size,
                previewSnippet = lastText,
                pinnedCacheId = pinnedCacheId
            )

            // 1. Атомарная потоковая запись тела сессии через AtomicFile
            val sessionFile = File(sessionsDirectory, "session_$sessionId.json")
            val sessionAtomic = AtomicFile(sessionFile)
            val sessionData = ChatSessionData(metadata = metadata, messages = messages)

            val sessionFos: FileOutputStream = sessionAtomic.startWrite()
            var sessionSuccess = false
            try {
                val bos = BufferedOutputStream(sessionFos)
                json.encodeToStream(sessionData, bos)
                bos.flush()
                sessionAtomic.finishWrite(sessionFos)
                sessionSuccess = true
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "AtomicSessionStore: Ошибка записи сессии $sessionId", e)
                return@withContext currentIndex
            } finally {
                if (!sessionSuccess) {
                    sessionAtomic.failWrite(sessionFos)
                }
            }

            // 2. Атомарное обновление и сброс на диск главного индекса сессий
            val updatedSessions = currentIndex.sessions.filterNot { it.id == sessionId }.toMutableList()
            updatedSessions.add(0, metadata) // Активная сессия поднимается наверх списка
            currentIndex = currentIndex.copy(activeSessionId = sessionId, sessions = updatedSessions)

            val indexFos: FileOutputStream = indexAtomicFile.startWrite()
            var indexSuccess = false
            try {
                val bos = BufferedOutputStream(indexFos)
                json.encodeToStream(currentIndex, bos)
                bos.flush()
                indexAtomicFile.finishWrite(indexFos)
                indexSuccess = true
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "AtomicSessionStore: Ошибка записи индекса сессий", e)
            } finally {
                if (!indexSuccess) {
                    indexAtomicFile.failWrite(indexFos)
                }
            }

            AppLogger.d(AppLogger.TAG_VM, "AtomicSessionStore: Сессия $sessionId сохранена (сообщений: ${messages.size}, кэш: $pinnedCacheId)")
            currentIndex
        }
    }

    /**
     * Удаление сессии и ее файла с физического диска.
     */
    suspend fun removeSession(sessionId: String): SessionsIndex = withContext(Dispatchers.IO + NonCancellable) {
        ioMutex.withLock {
            val sessionFile = File(sessionsDirectory, "session_$sessionId.json")
            AtomicFile(sessionFile).delete()

            var currentIndex = loadIndexInternal()
            val filtered = currentIndex.sessions.filterNot { it.id == sessionId }
            val nextActiveId = if (currentIndex.activeSessionId == sessionId) filtered.firstOrNull()?.id else currentIndex.activeSessionId

            currentIndex = currentIndex.copy(activeSessionId = nextActiveId, sessions = filtered)

            val indexFos: FileOutputStream = indexAtomicFile.startWrite()
            var indexSuccess = false
            try {
                val bos = BufferedOutputStream(indexFos)
                json.encodeToStream(currentIndex, bos)
                bos.flush()
                indexAtomicFile.finishWrite(indexFos)
                indexSuccess = true
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "AtomicSessionStore: Ошибка обновления индекса при удалении", e)
            } finally {
                if (!indexSuccess) {
                    indexAtomicFile.failWrite(indexFos)
                }
            }

            AppLogger.i(AppLogger.TAG_VM, "AtomicSessionStore: Сессия $sessionId удалена.")
            currentIndex
        }
    }

    private fun loadIndexInternal(): SessionsIndex {
        val baseFile = indexAtomicFile.baseFile
        if (!baseFile.exists() || baseFile.length() == 0L) return SessionsIndex()
        return try {
            BufferedInputStream(indexAtomicFile.openRead()).use { stream ->
                json.decodeFromStream<SessionsIndex>(stream)
            }
        } catch (_: Exception) {
            SessionsIndex()
        }
    }
}