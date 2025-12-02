package org.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class StructuredAnswer(
    val answer: String,
    val details: String,
    val language: String,
)
