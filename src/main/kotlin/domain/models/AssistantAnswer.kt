package org.example.domain.models

/**
 * Ответ ассистента в домене.
 *
 * text     - текстовое содержимое, которое выводим в консоль
 * model    - модель, которая ответила
 * rawJson  - сырой JSON ответа от Anthropic
 */
data class AssistantAnswer(
    val text: String,
    val model: String,
    val rawJson: String
)
