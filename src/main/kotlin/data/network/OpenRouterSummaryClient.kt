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
 * Исключение при ошибке суммаризации.
 */
class SummaryException(message: String) : Exception(message)

/**
 * Клиент для суммаризации через OpenRouter с бесплатными моделями.
 * Поддерживает fallback на альтернативные модели при rate limit.
 */
class OpenRouterSummaryClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    private val primaryModel: String = "nex-agi/deepseek-v3.1-nex-n1:free",
) : SummaryClient {

    companion object {
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L

        private val FALLBACK_MODELS = listOf(
            "nex-agi/deepseek-v3.1-nex-n1:free",
            "mistralai/devstral-2512:free",
            "google/gemini-2.0-flash-exp:free",
            "deepseek/deepseek-r1:free",
            "qwen/qwen3-4b:free"
        )

        private const val SUMMARY_SYSTEM_PROMPT = """Сожми диалог в 2-4 предложения. Сохрани ТОЛЬКО: ключевые факты, имена, числа, решения. Удали всё лишнее. Пиши на языке диалога. Отвечай ТОЛЬКО текстом резюме."""
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

        // Все модели и попытки исчерпаны - выбрасываем исключение
        // чтобы не сохранять некорректный summary
        throw SummaryException("Не удалось создать summary - все модели недоступны")
    }

    private suspend fun trySummarize(dialogText: String, model: String, attempt: Int): String? {
        val messages = listOf(
            OpenRouterMessageDto(role = "system", content = SUMMARY_SYSTEM_PROMPT),
            OpenRouterMessageDto(role = "user", content = "Резюмируй диалог:\n\n$dialogText")
        )

        val body = OpenRouterRequestDto(
            model = model,
            messages = messages,
            maxTokens = 150,
            temperature = 0.2
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
                // Тихо пробуем следующую модель/попытку
                return null
            }

            val content = dto.choices?.firstOrNull()?.message?.content?.trim()

            // Проверяем, что получили осмысленный ответ
            if (content.isNullOrBlank() || content.length < 10) {
                return null
            }

            content
        } catch (e: Exception) {
            // Тихо пробуем следующую модель/попытку
            null
        }
    }
}
