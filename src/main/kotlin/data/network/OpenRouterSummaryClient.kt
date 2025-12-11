package org.example.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Клиент для суммаризации через OpenRouter с бесплатными моделями.
 * Поддерживает fallback на альтернативные модели при rate limit.
 */
class OpenRouterSummaryClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    private val primaryModel: String = "meta-llama/llama-3.2-3b-instruct:free",
) : SummaryClient {

    companion object {
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L

        // Список fallback моделей при rate limit
        private val FALLBACK_MODELS = listOf(
            "google/gemini-2.0-flash-exp:free",
            "deepseek/deepseek-r1:free",
            "qwen/qwen3-4b:free"
        )

        private const val SUMMARY_SYSTEM_PROMPT = """Ты — помощник для сжатия диалогов. Создай КРАТКОЕ резюме диалога.

ПРАВИЛА:
1. Сохрани ключевые факты, решения, имена, числа
2. Убери повторы и несущественные детали
3. Пиши кратко (максимум 3-5 предложений)
4. Отвечай ТОЛЬКО текстом резюме
5. Пиши на том же языке, что и диалог"""
    }

    override suspend fun summarize(dialogText: String): String {
        val modelsToTry = listOf(primaryModel) + FALLBACK_MODELS

        for (model in modelsToTry) {
            for (attempt in 1..MAX_RETRIES) {
                val result = trySummarize(dialogText, model, attempt)
                if (result != null) {
                    return result
                }
                // Задержка перед retry (кроме последней попытки)
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        // Все модели и попытки исчерпаны
        return "Предыдущий диалог (не удалось суммаризировать - все модели недоступны)."
    }

    private suspend fun trySummarize(dialogText: String, model: String, attempt: Int): String? {
        // Reuse DTOs from OpenRouterClient
        val messages = listOf(
            OpenRouterMessageDto(role = "system", content = SUMMARY_SYSTEM_PROMPT),
            OpenRouterMessageDto(role = "user", content = "Резюмируй диалог:\n\n$dialogText")
        )

        val body = OpenRouterRequestDto(
            model = model,
            messages = messages,
            maxTokens = 256,
            temperature = 0.3
        )

        return try {
            val httpResponse = http.post(API_URL) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/llm-chat-app")
                header("X-Title", "LLM Chat Summarizer")
                setBody(body)
            }

            val responseText = httpResponse.body<String>()
            val dto = json.decodeFromString(OpenRouterResponseDto.serializer(), responseText)

            if (dto.error != null) {
                val errorCode = dto.error.code
                val errorMsg = dto.error.message
                System.err.println("OpenRouter error ($model, attempt $attempt): $errorCode - $errorMsg")

                // При rate limit (429) возвращаем null чтобы попробовать другую модель
                if (errorCode == 429) {
                    return null
                }
                // Другие ошибки тоже пробуем retry
                return null
            }

            dto.choices?.firstOrNull()?.message?.content?.trim()
                ?: "Диалог о различных темах."
        } catch (e: Exception) {
            System.err.println("OpenRouter exception ($model, attempt $attempt): ${e::class.simpleName}: ${e.message}")
            null
        }
    }
}
