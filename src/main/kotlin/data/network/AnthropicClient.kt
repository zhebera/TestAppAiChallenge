package org.example.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.data.network.LlmClient

/**
 * Низкоуровневый клиент для Anthropic.
 */
class AnthropicClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    override val model: String,
//    override val systemPrompt: String?,
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmResponse {
        val body = AnthropicRequestDto(
            model = model,
            system = request.systemPrompt,
            maxTokens = request.maxTokens,
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
        )
    }
}

@Serializable
data class AnthropicRequestDto(
    val model: String,
    val messages: List<AnthropicMessageDto>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int
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
    val content: List<AnthropicContentBlockDto> = emptyList()
)

@Serializable
data class AnthropicContentBlockDto(
    val type: String,
    val text: String? = null
)