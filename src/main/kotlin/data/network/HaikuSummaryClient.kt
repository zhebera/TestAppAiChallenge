package org.example.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicMessageContent
import org.example.data.api.AnthropicMessageDto
import org.example.data.api.AnthropicRequestDto
import org.example.data.api.AnthropicResponseDto

/**
 * Клиент для суммаризации через Claude Haiku.
 * Использует быстрый и недорогой Haiku для сжатия истории диалога.
 */
class HaikuSummaryClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String
) : SummaryClient {

    companion object {
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        private const val SUMMARY_SYSTEM_PROMPT = """Сожми диалог в 2-4 предложения. Сохрани ТОЛЬКО: ключевые факты, имена, числа, решения. Удали всё лишнее. Пиши на языке диалога. Отвечай ТОЛЬКО текстом резюме."""
    }

    override suspend fun summarize(dialogText: String): String {
        val messages = listOf(
            AnthropicMessageDto(
                role = "user",
                content = AnthropicMessageContent.Text("Резюмируй диалог:\n\n$dialogText")
            )
        )

        val body = AnthropicRequestDto(
            model = MODEL,
            system = SUMMARY_SYSTEM_PROMPT,
            maxTokens = 150,
            temperature = 0.2,
            stream = false,
            messages = messages
        )

        val httpResponse = http.post(API_URL) {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            setBody(body)
        }

        val responseText = httpResponse.body<String>()
        val dto = json.decodeFromString(AnthropicResponseDto.serializer(), responseText)

        if (dto.type == "error") {
            throw RuntimeException("Haiku summary failed: $responseText")
        }

        val content = dto.content
            .joinToString("") { it.text ?: "" }
            .trim()

        if (content.isBlank() || content.length < 10) {
            throw RuntimeException("Haiku returned empty or too short summary")
        }

        return content
    }
}
