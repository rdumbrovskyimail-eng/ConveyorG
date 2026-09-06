package com.conveyorg.data

import com.conveyorg.presentation.UiChatMessage
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Облегченные метаданные диалога для отображения в боковой шторке истории.
 * Не содержит тяжелых сообщений, что гарантирует мгновенную загрузку списка.
 */
@Serializable
data class ChatSessionMetadata(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый диалог",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val previewSnippet: String = "",
    /** Зафиксированный ID кэша Google TPU (если в сессии есть файлы > 100 КБ) */
    val pinnedCacheId: String? = null
)

/**
 * Полный снимок сессии, сохраняемый в отдельный атомарный файл session_{id}.json.
 */
@Serializable
data class ChatSessionData(
    val metadata: ChatSessionMetadata,
    val messages: List<UiChatMessage> = emptyList()
)

/**
 * Главный индекс всех существующих сессий приложения (sessions_index.json).
 */
@Serializable
data class SessionsIndex(
    val activeSessionId: String? = null,
    val sessions: List<ChatSessionMetadata> = emptyList()
)