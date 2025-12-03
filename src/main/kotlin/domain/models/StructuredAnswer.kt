package org.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class StructuredAnswer(
    val phase: String,
    val message: String,
    val document: String,
)
