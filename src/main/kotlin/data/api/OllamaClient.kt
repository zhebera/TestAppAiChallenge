package org.example.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.slf4j.LoggerFactory

/**
 * Ollama LLM client implementation
 *
 * Implements LlmClient interface for local Ollama instances.
 * Default configuration:
 * - Host: http://localhost:11434
 * - Model: qwen2.5:7b (hardcoded for MVP)
 * - Timeout: 120 seconds (configured in HttpClient)
 */
class OllamaClient(
    private val http: HttpClient,
    private val json: Json,
    private val host: String = System.getenv("OLLAMA_HOST") ?: DEFAULT_HOST,
    override val model: String = DEFAULT_MODEL
) : LlmClient {

    private val logger = LoggerFactory.getLogger(OllamaClient::class.java)

    companion object {
        const val DEFAULT_HOST = "http://localhost:11434"
        const val DEFAULT_MODEL = "qwen2.5:7b"
        private const val CHAT_ENDPOINT = "/api/chat"
        private const val TAGS_ENDPOINT = "/api/tags"
    }

    /**
     * Send a request to Ollama and get the complete response
     */
    override suspend fun send(request: LlmRequest): LlmResponse {
        logger.info("Ollama request: model=$model, messages=${request.messages.size}")

        val requestBody = OllamaRequestDto(
            model = model,
            messages = buildMessages(request),
            stream = false,
            options = request.temperature?.let {
                OllamaOptionsDto(temperature = it)
            }
        )

        val startTime = System.currentTimeMillis()

        try {
            val httpResponse = http.post("$host$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val responseText = httpResponse.body<String>()

            // Ollama returns multiple JSON objects (one per line) even with stream=false
            // Parse and accumulate all chunks to get the final message
            val dto = parseOllamaStreamingResponse(responseText)

            val latency = System.currentTimeMillis() - startTime
            logger.info("Ollama response: success, latency=${latency}ms, tokens=${dto.evalCount}")

            // Ollama returns simple text, wrap it in LlmResponse
            return LlmResponse(
                model = dto.model,
                text = dto.message.content.trim(),
                rawJson = dto.message.content,  // No JSON parsing for local models
                inputTokens = dto.promptEvalCount,
                outputTokens = dto.evalCount,
                stopReason = if (dto.done) "stop" else null
            )

        } catch (e: ConnectTimeoutException) {
            logger.error("Ollama connection timeout", e)
            throw IllegalStateException(
                "Cannot connect to Ollama at $host\n" +
                "Is Ollama running? Try: ollama serve"
            )
        } catch (e: HttpRequestTimeoutException) {
            logger.error("Ollama request timeout", e)
            throw IllegalStateException(
                "Request timeout (>120s)\n" +
                "The prompt may be too long or the model is overloaded"
            )
        } catch (e: Exception) {
            logger.error("Ollama request failed", e)
            throw IllegalStateException("Ollama error: ${e.message}", e)
        }
    }

    /**
     * Send a streaming request (future feature)
     * For MVP, returns empty flow
     */
    override fun sendStream(request: LlmRequest): Flow<StreamEvent> = flow {
        logger.warn("Ollama streaming not implemented yet, falling back to non-streaming")

        // Fallback to non-streaming for MVP
        val response = send(request)

        // Emit complete event
        emit(StreamEvent.Complete(
            model = response.model,
            fullText = response.text,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            stopReason = response.stopReason
        ))
    }

    /**
     * Check if Ollama is running and accessible
     */
    suspend fun checkHealth(): Boolean {
        return try {
            val response = http.get("$host$TAGS_ENDPOINT")
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.debug("Ollama health check failed: ${e.message}")
            false
        }
    }

    /**
     * Check if a specific model is available
     */
    suspend fun checkModelExists(modelName: String): Boolean {
        return try {
            val response = http.get("$host$TAGS_ENDPOINT")
            val responseText = response.body<String>()
            val tags = json.decodeFromString(OllamaTagsResponseDto.serializer(), responseText)

            tags.models.any { it.name == modelName }
        } catch (e: Exception) {
            logger.error("Failed to check model existence", e)
            false
        }
    }

    /**
     * Get list of available models
     */
    suspend fun listModels(): List<OllamaModelInfoDto> {
        return try {
            val response = http.get("$host$TAGS_ENDPOINT")
            val responseText = response.body<String>()
            val tags = json.decodeFromString(OllamaTagsResponseDto.serializer(), responseText)
            tags.models
        } catch (e: Exception) {
            logger.error("Failed to list models", e)
            emptyList()
        }
    }

    /**
     * Parse Ollama streaming response (multiple JSON objects separated by newlines)
     * Accumulates all message chunks and returns the final complete response
     */
    private fun parseOllamaStreamingResponse(responseText: String): OllamaResponseDto {
        val lines = responseText.trim().lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            throw IllegalStateException("Empty response from Ollama")
        }

        // Accumulate message content from all chunks
        val messageBuilder = StringBuilder()
        var finalDto: OllamaResponseDto? = null

        for (line in lines) {
            try {
                val dto = json.decodeFromString(OllamaResponseDto.serializer(), line)

                // Accumulate message content
                if (dto.message.content.isNotBlank()) {
                    messageBuilder.append(dto.message.content)
                }

                // Keep the last dto with done=true for metadata
                if (dto.done) {
                    finalDto = dto
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse line: $line", e)
                // Continue to next line
            }
        }

        // Use the final dto with accumulated message
        val result = finalDto ?: throw IllegalStateException("No complete response from Ollama")

        return result.copy(
            message = OllamaMessageDto(
                role = result.message.role,
                content = messageBuilder.toString()
            )
        )
    }

    /**
     * Build Ollama messages from LlmRequest
     * Includes system prompt as a separate message if present
     */
    private fun buildMessages(request: LlmRequest): List<OllamaMessageDto> {
        val messages = mutableListOf<OllamaMessageDto>()

        // Add system prompt as first message if present
        if (!request.systemPrompt.isNullOrBlank()) {
            messages.add(
                OllamaMessageDto(
                    role = "system",
                    content = request.systemPrompt
                )
            )
        }

        // Add user/assistant messages
        messages.addAll(
            request.messages.map { msg ->
                OllamaMessageDto(
                    role = msg.role.name.toLowerCasePreservingASCIIRules(),
                    content = msg.content
                )
            }
        )

        return messages
    }

    /**
     * Estimate token count (rough approximation)
     * Used for warning about long prompts
     */
    fun estimateTokens(text: String): Int {
        // Rough estimate: 1 token ≈ 4 characters (for English text)
        return text.length / 4
    }
}
