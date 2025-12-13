package org.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class LlmMessage(
    val role: ChatRole,
    val content: String,
)
