package org.example.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LlmResponse(
    val model: String,
    val text: String,            // объединённый текст всех блоков
    val rawJson: String,         // оригинальный JSON от API
)