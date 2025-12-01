package org.example.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnthropicResponseDto(
    val id: String? = null,
    val type: String,
    val role: String? = null,
    val model: String? = null,
    val content: List<AnthropicContentBlockDto> = emptyList(),
)
