package org.example.data.scheduler

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.mcp.server.FootballNews
import java.net.HttpURLConnection
import java.net.URL

/**
 * Процессор новостей с использованием Claude API.
 * Выбирает топ-5 интересных новостей, переводит и создает краткую сводку.
 */
class NewsAiProcessor(
    private val apiKey: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-sonnet-4-20250514"

        private val SYSTEM_PROMPT = """
            Ты - спортивный журналист, специализирующийся на футболе.
            Твоя задача - анализировать новости и создавать краткую сводку на русском языке.

            Требования:
            1. Выбери 5 самых интересных и важных новостей из предоставленного списка
            2. Для каждой новости напиши краткую выжимку в 2-3 предложения
            3. Пиши ТОЛЬКО на русском языке
            4. Не используй заголовки - только суть новости
            5. Нумеруй новости от 1 до 5
            6. Фокусируйся на фактах: трансферы, результаты, травмы, важные события
            7. Если новости не особо интересные или их мало - напиши только о самых значимых

            Формат ответа:
            1. [Краткая выжимка первой новости в 2-3 предложения]

            2. [Краткая выжимка второй новости в 2-3 предложения]

            ... и так далее
        """.trimIndent()
    }

    /**
     * Обработать новости через Claude и получить сводку на русском.
     */
    fun processNews(news: List<FootballNews>): String? {
        if (news.isEmpty()) return null

        val newsText = news.mapIndexed { index, item ->
            """
            ${index + 1}. Title: ${item.title}
            Description: ${item.description}
            Source: ${item.sources}
            Leagues: ${item.leagues.joinToString(", ")}
            """.trimIndent()
        }.joinToString("\n\n")

        val userMessage = """
            Вот список футбольных новостей. Выбери 5 самых интересных и создай краткую сводку на русском:

            $newsText
        """.trimIndent()

        return try {
            callClaudeApi(userMessage)
        } catch (e: Exception) {
            null
        }
    }

    private fun callClaudeApi(userMessage: String): String? {
        val url = URL(API_URL)
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 60000

        val requestBody = """
            {
                "model": "$MODEL",
                "max_tokens": 1024,
                "system": ${json.encodeToString(kotlinx.serialization.serializer<String>(), SYSTEM_PROMPT)},
                "messages": [
                    {
                        "role": "user",
                        "content": ${json.encodeToString(kotlinx.serialization.serializer<String>(), userMessage)}
                    }
                ]
            }
        """.trimIndent()

        return try {
            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().readText()
                extractTextFromResponse(responseText)
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTextFromResponse(responseJson: String): String? {
        return try {
            val response = json.decodeFromString(ClaudeResponse.serializer(), responseJson)
            response.content.firstOrNull { it.type == "text" }?.text
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
private data class ClaudeResponse(
    val content: List<ContentBlock>
)

@Serializable
private data class ContentBlock(
    val type: String,
    val text: String? = null
)
