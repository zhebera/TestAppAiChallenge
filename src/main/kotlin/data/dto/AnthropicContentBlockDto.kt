package org.example.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnthropicContentBlockDto(
    val type: String,
    val text: String? = null,
)
