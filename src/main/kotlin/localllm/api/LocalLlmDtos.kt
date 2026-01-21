package org.example.localllm.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Запрос к чату с локальной LLM
 */
@Serializable
data class ChatRequest(
    val message: String,
    @SerialName("conversation_id")
    val conversationId: String? = null,
    val history: List<ChatMessage>? = null,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val model: String? = null,
    val stream: Boolean = false
)

/**
 * Сообщение в истории чата
 */
@Serializable
data class ChatMessage(
    val role: String,  // "user", "assistant", "system"
    val content: String
)

/**
 * Ответ от чата
 */
@Serializable
data class ChatResponse(
    val response: String,
    val model: String,
    @SerialName("conversation_id")
    val conversationId: String? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("processing_time_ms")
    val processingTimeMs: Long? = null
)

/**
 * Событие стриминга (Server-Sent Events)
 */
@Serializable
data class StreamChunk(
    val type: String,  // "delta", "done", "error"
    val content: String? = null,
    val model: String? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null
)

/**
 * Статус здоровья сервера
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    @SerialName("ollama_available")
    val ollamaAvailable: Boolean,
    @SerialName("default_model")
    val defaultModel: String,
    @SerialName("available_models")
    val availableModels: List<String> = emptyList()
)

/**
 * Информация о модели
 */
@Serializable
data class ModelInfo(
    val name: String,
    val size: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    val digest: String? = null
)

/**
 * Список моделей
 */
@Serializable
data class ModelsResponse(
    val models: List<ModelInfo>
)

/**
 * Ответ с ошибкой
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int? = null
)
