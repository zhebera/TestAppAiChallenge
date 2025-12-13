package org.example.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse

/**
 * Клиент для OpenRouter API с бесплатными моделями.
 * Используется для суммаризации истории диалога.
 *
 * Бесплатные модели (лимит 50 req/day, 1000 req/day с $10+ на балансе):
 * - meta-llama/llama-3.2-3b-instruct:free
 * - google/gemini-2.0-flash-exp:free
 * - deepseek/deepseek-r1:free
 */
class OpenRouterClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    override val model: String = "meta-llama/llama-3.2-3b-instruct:free",
) : LlmClient {

    companion object {
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }

    override suspend fun send(request: LlmRequest): LlmResponse {
        val messages = buildList {
            // System prompt как первое сообщение
            request.systemPrompt?.let { systemPrompt ->
                add(OpenRouterMessageDto(role = "system", content = systemPrompt))
            }
            // Остальные сообщения
            request.messages.forEach { msg ->
                add(OpenRouterMessageDto(
                    role = msg.role.name.lowercase(),
                    content = msg.content
                ))
            }
        }

        val body = OpenRouterRequestDto(
            model = model,
            messages = messages,
            maxTokens = request.maxTokens,
            temperature = request.temperature
        )

        val httpResponse = http.post(API_URL) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "https://github.com/your-app")
            header("X-Title", "LLM Chat App")
            setBody(body)
        }

        val responseText = httpResponse.body<String>()
        val dto = json.decodeFromString(OpenRouterResponseDto.serializer(), responseText)

        if (dto.error != null) {
            throw IllegalStateException("OpenRouter API error: ${dto.error.message}")
        }

        val content = dto.choices?.firstOrNull()?.message?.content ?: ""

        return LlmResponse(
            model = dto.model ?: model,
            text = content.trim(),
            rawJson = responseText,
            inputTokens = dto.usage?.promptTokens,
            outputTokens = dto.usage?.completionTokens,
            stopReason = dto.choices?.firstOrNull()?.finishReason
        )
    }

    override fun sendStream(request: LlmRequest): Flow<StreamEvent> = flow {
        val response = send(request)
        emit(StreamEvent.Complete(
            model = response.model,
            fullText = response.text,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            stopReason = response.stopReason
        ))
    }
}
