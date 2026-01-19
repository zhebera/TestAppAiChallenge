package org.example.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ollama API Request DTO
 * https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
@Serializable
data class OllamaRequestDto(
    val model: String,
    val messages: List<OllamaMessageDto>,
    val stream: Boolean = false,
    val options: OllamaOptionsDto? = null
)

/**
 * Ollama message format
 */
@Serializable
data class OllamaMessageDto(
    val role: String,  // "user", "assistant", or "system"
    val content: String
)

/**
 * Ollama model options
 * https://github.com/ollama/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values
 */
@Serializable
data class OllamaOptionsDto(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("num_ctx") val numCtx: Int? = null  // context window size
)

/**
 * Ollama API Response DTO
 */
@Serializable
data class OllamaResponseDto(
    val model: String,
    val message: OllamaMessageDto,
    val done: Boolean,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null
)

/**
 * Ollama tags/models list response
 */
@Serializable
data class OllamaTagsResponseDto(
    val models: List<OllamaModelInfoDto>
)

/**
 * Ollama model info
 */
@Serializable
data class OllamaModelInfoDto(
    val name: String,
    val size: Long,
    @SerialName("modified_at") val modifiedAt: String,
    val digest: String? = null,
    val details: OllamaModelDetailsDto? = null
)

/**
 * Ollama model details
 */
@Serializable
data class OllamaModelDetailsDto(
    val format: String? = null,
    val family: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null
)

/**
 * Ollama streaming event (for future streaming support)
 */
@Serializable
data class OllamaStreamEventDto(
    val model: String,
    val message: OllamaMessageDto? = null,
    val done: Boolean,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)
