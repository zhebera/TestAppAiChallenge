package org.example.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.data.dto.AnthropicErrorResponseDto
import org.example.data.dto.AnthropicMessageDto
import org.example.data.dto.AnthropicRequestDto
import org.example.data.dto.AnthropicResponseDto

/**
 * Низкоуровневый клиент для Anthropic.
 */
class AnthropicApi(
    private val client: HttpClient,
    private val json: Json,
    private val apiKey: String
) {

    suspend fun sendMessages(
        model: String,
        messages: List<AnthropicMessageDto>,
        maxTokens: Int
    ): AnthropicResponseDto {
        val requestBody = AnthropicRequestDto(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
        )

        val httpResponse = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody)
        }

        val bodyText = httpResponse.bodyAsText()

//        println("Anthropic raw response: ${httpResponse.status} | $bodyText")

        // Сначала просто парсим JSON как дерево
        val root = json.parseToJsonElement(bodyText).jsonObject
        val type = root["type"]?.jsonPrimitive?.content

        if (type == "error") {
            val error = json.decodeFromJsonElement<AnthropicErrorResponseDto>(root)
            throw IllegalStateException(
                "Anthropic error (${error.error.type}): ${error.error.message} [request_id=${error.requestId}]"
            )
        }

        return json.decodeFromJsonElement<AnthropicResponseDto>(root)
    }
}