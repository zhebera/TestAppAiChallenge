package org.example.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnthropicMessageDto(
    val role: String,
    val content: String
)
