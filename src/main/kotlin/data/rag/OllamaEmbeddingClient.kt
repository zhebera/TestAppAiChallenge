package org.example.data.rag

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент для генерации эмбеддингов через Ollama API.
 * Ollama должна быть запущена локально (ollama serve).
 */
class OllamaEmbeddingClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "mxbai-embed-large"
) {
    companion object {
        const val EMBEDDING_DIMENSION = 1024  // mxbai-embed-large выдаёт 1024 измерения
        private const val CONCURRENT_REQUESTS = 3   // Параллельные запросы к Ollama
        private const val MAX_TEXT_LENGTH = 200     // Максимальная длина текста для эмбеддинга
    }

    /**
     * Генерация эмбеддинга для одного текста.
     * Использует новый Ollama API (/api/embed).
     */
    suspend fun embed(text: String): FloatArray {
        // Обрезаем слишком длинный текст
        val truncatedText = if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) else text

        val response = httpClient.post("$baseUrl/api/embed") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OllamaEmbedRequest.serializer(), OllamaEmbedRequest(model, truncatedText)))
        }

        val responseBody: String = response.body()

        // Проверяем на пустой ответ
        if (responseBody.isBlank()) {
            throw RuntimeException("Ollama returned empty response for text (${text.take(50)}...)")
        }

        // Проверяем на ошибку от Ollama
        if (responseBody.contains("\"error\"")) {
            throw RuntimeException("Ollama error: $responseBody")
        }

        try {
            val result: OllamaEmbedResponse = json.decodeFromString(responseBody)
            // embeddings - это массив массивов, берём первый
            return result.embeddings.first().toFloatArray()
        } catch (e: Exception) {
            // Показываем начало ответа для отладки
            val preview = responseBody.take(200)
            throw RuntimeException("Failed to parse Ollama response: $preview... Error: ${e.message}")
        }
    }

    /**
     * Генерация эмбеддингов для списка текстов с параллельным выполнением.
     * Разбивает на батчи и обрабатывает параллельно для скорости.
     */
    suspend fun embedBatch(texts: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val results = mutableListOf<FloatArray>()
        val batches = texts.chunked(CONCURRENT_REQUESTS)

        batches.forEachIndexed { batchIndex, batch ->
            val batchResults = coroutineScope {
                batch.map { text ->
                    async { embed(text) }
                }.awaitAll()
            }
            results.addAll(batchResults)
            onProgress(minOf((batchIndex + 1) * CONCURRENT_REQUESTS, texts.size), texts.size)
        }

        return results
    }

    /**
     * Проверка доступности Ollama.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            httpClient.get("$baseUrl/api/tags")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Проверка наличия модели.
     */
    suspend fun isModelAvailable(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/api/tags")
            val body: String = response.body()
            body.contains(model)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Запрос для нового Ollama API (/api/embed).
 */
@Serializable
private data class OllamaEmbedRequest(
    val model: String,
    val input: String  // Новый API использует 'input' вместо 'prompt'
)

/**
 * Ответ от нового Ollama API (/api/embed).
 */
@Serializable
private data class OllamaEmbedResponse(
    val model: String,
    val embeddings: List<List<Float>>  // Массив массивов (для батч-запросов)
)