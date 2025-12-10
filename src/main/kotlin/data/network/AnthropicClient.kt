package org.example.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.toLowerCasePreservingASCIIRules
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent

/**
 * Низкоуровневый клиент для Anthropic.
 */
class AnthropicClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    override val model: String,
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmResponse {
        val body = AnthropicRequestDto(
            model = model,
            system = request.systemPrompt,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            stream = false,
            messages = request.messages.map {
                AnthropicMessageDto(it.role.name.toLowerCasePreservingASCIIRules(), it.content)
            }
        )

        val httpResponse = http.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body)
        }

        val responseText = httpResponse.body<String>()

        val dto = json.decodeFromString(AnthropicResponseDto.serializer(), responseText)

        if (dto.type == "error") {
            throw IllegalStateException("Anthropic API error: $responseText")
        }

        val combinedText = dto.content.joinToString("") { it.text ?: "" }

        return LlmResponse(
            model = dto.model ?: request.model,
            text = combinedText.trim(),
            rawJson = combinedText,
            inputTokens = dto.usage?.inputTokens,
            outputTokens = dto.usage?.outputTokens,
            stopReason = dto.stopReason,
        )
    }

    override fun sendStream(request: LlmRequest): Flow<StreamEvent> = flow {
        val body = AnthropicRequestDto(
            model = model,
            system = request.systemPrompt,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            stream = true,
            messages = request.messages.map {
                AnthropicMessageDto(it.role.name.toLowerCasePreservingASCIIRules(), it.content)
            }
        )

        http.preparePost("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body)
        }.execute { httpResponse ->
            val channel = httpResponse.bodyAsChannel()
            val fullText = StringBuilder()
            var responseModel: String = model
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var stopReason: String? = null

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                // SSE format: lines starting with "data: " contain JSON
                if (line.startsWith("data: ")) {
                    val jsonData = line.removePrefix("data: ").trim()
                    if (jsonData.isEmpty() || jsonData == "[DONE]") continue

                    try {
                        val event = json.decodeFromString(StreamEventDto.serializer(), jsonData)

                        when (event.type) {
                            "message_start" -> {
                                // Получаем информацию о сообщении и input tokens
                                event.message?.let { msg ->
                                    responseModel = msg.model ?: model
                                    inputTokens = msg.usage?.inputTokens
                                }
                            }
                            "content_block_delta" -> {
                                // Текстовый delta
                                event.delta?.text?.let { text ->
                                    fullText.append(text)
                                    emit(StreamEvent.TextDelta(text))
                                }
                            }
                            "message_delta" -> {
                                // Финальные метаданные (output tokens, stop reason)
                                event.usage?.let { usage ->
                                    outputTokens = usage.outputTokens
                                }
                                event.delta?.stopReason?.let { reason ->
                                    stopReason = reason
                                }
                            }
                            "message_stop" -> {
                                // Стрим завершён
                            }
                            "error" -> {
                                throw IllegalStateException("Anthropic streaming error: $jsonData")
                            }
                        }
                    } catch (e: Exception) {
                        if (e is IllegalStateException) throw e
                        // Игнорируем ошибки парсинга неизвестных событий
                    }
                }
            }

            // Emit финальное событие с полным текстом и метаданными
            emit(StreamEvent.Complete(
                model = responseModel,
                fullText = fullText.toString().trim(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                stopReason = stopReason
            ))
        }
    }
}

@Serializable
data class AnthropicRequestDto(
    val model: String,
    val messages: List<AnthropicMessageDto>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false
)

@Serializable
data class AnthropicMessageDto(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicResponseDto(
    val id: String? = null,
    val type: String,
    val role: String? = null,
    val model: String? = null,
    val content: List<AnthropicContentBlockDto> = emptyList(),
    val usage: AnthropicUsageDto? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicContentBlockDto(
    val type: String,
    val text: String? = null
)

@Serializable
data class AnthropicUsageDto(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

// --- Streaming Event DTOs ---

@Serializable
data class StreamEventDto(
    val type: String,
    val message: StreamMessageDto? = null,
    val delta: StreamDeltaDto? = null,
    val usage: AnthropicUsageDto? = null,
    val index: Int? = null,
)

@Serializable
data class StreamMessageDto(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val model: String? = null,
    val usage: AnthropicUsageDto? = null,
)

@Serializable
data class StreamDeltaDto(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)