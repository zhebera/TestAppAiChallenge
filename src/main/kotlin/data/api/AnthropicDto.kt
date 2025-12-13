package org.example.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicRequestDto(
    val model: String,
    val messages: List<AnthropicMessageDto>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false
)

@Serializable
data class AnthropicMessageDto(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicResponseDto(
    val id: String? = null,
    val type: String,
    val role: String? = null,
    val model: String? = null,
    val content: List<AnthropicContentBlockDto> = emptyList(),
    val usage: AnthropicUsageDto? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicContentBlockDto(
    val type: String,
    val text: String? = null
)

@Serializable
data class AnthropicUsageDto(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

@Serializable
data class StreamEventDto(
    val type: String,
    val message: StreamMessageDto? = null,
    val delta: StreamDeltaDto? = null,
    val usage: AnthropicUsageDto? = null,
    val index: Int? = null,
)

@Serializable
data class StreamMessageDto(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val model: String? = null,
    val usage: AnthropicUsageDto? = null,
)

@Serializable
data class StreamDeltaDto(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)
