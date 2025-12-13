package org.example.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenRouterRequestDto(
    val model: String,
    val messages: List<OpenRouterMessageDto>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false
)

@Serializable
data class OpenRouterMessageDto(
    val role: String,
    val content: String
)

@Serializable
data class OpenRouterResponseDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenRouterChoiceDto>? = null,
    val usage: OpenRouterUsageDto? = null,
    val error: OpenRouterErrorDto? = null
)

@Serializable
data class OpenRouterChoiceDto(
    val index: Int? = null,
    val message: OpenRouterMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenRouterUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

@Serializable
data class OpenRouterErrorDto(
    val message: String? = null,
    val code: Int? = null
)
