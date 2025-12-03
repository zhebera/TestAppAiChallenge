package org.example.domain.models

/**
 * Ответ ассистента в домене.
 *
 * text - то, что показать как реплику ассистента (message)
 * model - модель, которая ответила
 * rawJson - сырой JSON ответа от Anthropic
 * phase - "questions" / "ready"
 * document - готовый ответ, если есть
 */
data class AssistantAnswer(
    val text: String,
    val model: String,
    val rawJson: String,
    val phase: String,
    val document: String,
)
