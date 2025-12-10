package org.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class LlmAnswer(
    val model: String,
    val rawJson: String,
    val phase: String,
    val document: String,
    val message: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val stopReason: String? = null,
)