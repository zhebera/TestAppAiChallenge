package org.example.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LlmResponse(
    val model: String,
    val text: String,            // объединённый текст всех блоков
    val rawJson: String,         // оригинальный JSON от API
    val inputTokens: Int? = null,  // токены на запрос
    val outputTokens: Int? = null, // токены на ответ
    val stopReason: String? = null, // причина остановки (end_turn, max_tokens, etc.)
)