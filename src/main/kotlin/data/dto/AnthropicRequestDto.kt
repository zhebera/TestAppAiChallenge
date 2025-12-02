package org.example.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicRequestDto(
    val model: String,
    val messages: List<AnthropicMessageDto>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int,
)
