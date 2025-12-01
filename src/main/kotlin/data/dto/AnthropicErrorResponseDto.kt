package org.example.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicErrorDetailsDto(
    val type: String,
    val message: String
)

@Serializable
data class AnthropicErrorResponseDto(
    val type: String,
    val error: AnthropicErrorDetailsDto,
    @SerialName("request_id") val requestId: String? = null
)
