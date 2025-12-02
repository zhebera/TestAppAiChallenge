package org.example.domain.models

/**
 * Доменная модель сообщения в чате (user / assistant).
 */
data class ChatMessage(
    val role: String,       // "system", "user", "assistant"
    val content: String
)
