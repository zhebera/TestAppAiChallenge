package org.example.data.dto

import kotlinx.serialization.Serializable
import org.example.domain.models.LlmMessage

@Serializable
data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val systemPrompt: String?,
    val maxTokens: Int = 1024,
    val temperature: Double? = null,
)