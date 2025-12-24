package org.example.data.rag

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/**
 * Результат реранкинга.
 */
data class RerankedResult(
    val original: SearchResult,
    val rerankedScore: Float,     // Новый скор после реранкинга
    val originalScore: Float,     // Исходный cosine similarity
    val wasFiltered: Boolean      // Был ли результат отфильтрован
)

/**
 * Сравнение результатов с реранкингом и без.
 */
data class ComparisonResult(
    val query: String,
    val withoutReranking: List<SearchResult>,
    val withReranking: List<RerankedResult>,
    val filteredCount: Int,
    val reorderedCount: Int,
    val threshold: Float
)

/**
 * Конфигурация реранкера.
 */
data class RerankerConfig(
    val method: RerankerMethod = RerankerMethod.CROSS_ENCODER,
    val threshold: Float = 0.4f,            // Порог отсечения после реранкинга
    val useKeywordBoost: Boolean = true,    // Дополнительный буст по ключевым словам
    val maxCandidates: Int = 10             // Сколько кандидатов брать для реранкинга
)

/**
 * Метод реранкинга.
 */
enum class RerankerMethod {
    CROSS_ENCODER,     // Кросс-кодирование через эмбеддинги (query+doc)
    LLM_SCORING,       // Оценка через LLM (более точный, но медленный)
    KEYWORD_HYBRID     // Гибридный: cosine + ключевые слова
}

/**
 * Сервис реранкинга и фильтрации результатов RAG.
 *
 * Реранкинг улучшает качество результатов путём:
 * 1. Повторной оценки релевантности каждого результата
 * 2. Фильтрации нерелевантных результатов по порогу
 * 3. Пересортировки результатов по новым скорам
 */
class RerankerService(
    private val httpClient: HttpClient,
    private val json: Json,
    private val embeddingClient: OllamaEmbeddingClient,
    private val baseUrl: String = "http://localhost:11434",
    private val llmModel: String = "llama3.2"  // Модель для LLM-scoring
) {
    companion object {
        private const val CONCURRENT_RERANK = 3
    }

    /**
     * Основной метод реранкинга результатов.
     */
    suspend fun rerank(
        query: String,
        results: List<SearchResult>,
        config: RerankerConfig = RerankerConfig()
    ): List<RerankedResult> {
        if (results.isEmpty()) return emptyList()

        // Берём топ кандидатов для реранкинга
        val candidates = results.take(config.maxCandidates)

        // Применяем выбранный метод реранкинга
        val reranked = when (config.method) {
            RerankerMethod.CROSS_ENCODER -> rerankWithCrossEncoder(query, candidates)
            RerankerMethod.LLM_SCORING -> rerankWithLlm(query, candidates)
            RerankerMethod.KEYWORD_HYBRID -> rerankWithKeywordHybrid(query, candidates, config.useKeywordBoost)
        }

        // Фильтруем по порогу и сортируем
        return reranked
            .map { it.copy(wasFiltered = it.rerankedScore < config.threshold) }
            .sortedByDescending { it.rerankedScore }
    }

    /**
     * Фильтрация результатов по порогу.
     */
    fun filterByThreshold(
        results: List<RerankedResult>,
        threshold: Float
    ): List<RerankedResult> {
        return results.filter { it.rerankedScore >= threshold }
    }

    /**
     * Сравнение результатов с реранкингом и без.
     */
    suspend fun compare(
        query: String,
        results: List<SearchResult>,
        config: RerankerConfig = RerankerConfig()
    ): ComparisonResult {
        val reranked = rerank(query, results, config)
        val filtered = reranked.filter { !it.wasFiltered }

        // Подсчитываем сколько результатов изменили позицию
        var reorderedCount = 0
        filtered.forEachIndexed { newIndex, item ->
            val originalIndex = results.indexOfFirst { it.chunk.id == item.original.chunk.id }
            if (originalIndex != newIndex) reorderedCount++
        }

        return ComparisonResult(
            query = query,
            withoutReranking = results,
            withReranking = reranked,
            filteredCount = reranked.count { it.wasFiltered },
            reorderedCount = reorderedCount,
            threshold = config.threshold
        )
    }

    // === Методы реранкинга ===

    /**
     * Реранкинг через улучшенный bi-encoder.
     * Использует более длинный контекст документа и добавляет keyword boost.
     *
     * Подход:
     * 1. Берём больше текста документа для эмбеддинга (до 300 символов)
     * 2. Пересчитываем косинусное сходство с расширенным контекстом
     * 3. Добавляем буст за точное совпадение ключевых слов
     */
    private suspend fun rerankWithCrossEncoder(
        query: String,
        results: List<SearchResult>
    ): List<RerankedResult> {
        // Получаем эмбеддинг запроса
        val queryEmbedding = embeddingClient.embed(query)
        val queryKeywords = extractKeywords(query)

        // Параллельно обрабатываем результаты
        val rerankedResults = coroutineScope {
            results.chunked(CONCURRENT_RERANK).flatMap { batch ->
                batch.map { result ->
                    async {
                        // Берём больше контекста документа для более точного эмбеддинга
                        val extendedContent = result.chunk.content.take(300)
                        val docEmbedding = embeddingClient.embed(extendedContent)

                        // Пересчитываем косинусное сходство с расширенным контекстом
                        val semanticScore = cosineSimilarity(queryEmbedding, docEmbedding)

                        // Добавляем keyword boost (точные совпадения слов важны)
                        val keywordScore = calculateKeywordScore(queryKeywords, result.chunk.content)

                        // Комбинируем: 70% семантика + 30% ключевые слова
                        val finalScore = (semanticScore * 0.7f + keywordScore * 0.3f)

                        RerankedResult(
                            original = result,
                            rerankedScore = finalScore,
                            originalScore = result.similarity,
                            wasFiltered = false
                        )
                    }
                }.awaitAll()
            }
        }

        return rerankedResults
    }

    /**
     * Реранкинг через LLM (более точный, но медленный).
     * Просит LLM оценить релевантность документа запросу по шкале 0-10.
     */
    private suspend fun rerankWithLlm(
        query: String,
        results: List<SearchResult>
    ): List<RerankedResult> {
        return coroutineScope {
            results.chunked(CONCURRENT_RERANK).flatMap { batch ->
                batch.map { result ->
                    async {
                        val score = getLlmRelevanceScore(query, result.chunk.content)
                        RerankedResult(
                            original = result,
                            rerankedScore = score,
                            originalScore = result.similarity,
                            wasFiltered = false
                        )
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Получение оценки релевантности от LLM.
     */
    private suspend fun getLlmRelevanceScore(query: String, document: String): Float {
        val prompt = """
            |Rate the relevance of the following document to the query on a scale of 0 to 10.
            |Only respond with a single number (0-10), nothing else.
            |
            |Query: $query
            |
            |Document: ${document.take(300)}
            |
            |Relevance score (0-10):
        """.trimMargin()

        return try {
            val response = httpClient.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(
                    OllamaGenerateRequest.serializer(),
                    OllamaGenerateRequest(model = llmModel, prompt = prompt, stream = false)
                ))
            }

            val responseBody: String = response.body()
            val result: OllamaGenerateResponse = json.decodeFromString(responseBody)

            // Извлекаем число из ответа
            val scoreStr = result.response.trim()
            val score = scoreStr.toFloatOrNull() ?: scoreStr.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 5f
            (score / 10f).coerceIn(0f, 1f)  // Нормализуем к 0-1
        } catch (e: Exception) {
            // В случае ошибки возвращаем нейтральный скор
            0.5f
        }
    }

    /**
     * Гибридный реранкинг: cosine similarity + keyword boost.
     */
    private fun rerankWithKeywordHybrid(
        query: String,
        results: List<SearchResult>,
        useKeywordBoost: Boolean
    ): List<RerankedResult> {
        val queryWords = extractKeywords(query)

        return results.map { result ->
            var newScore = result.similarity

            if (useKeywordBoost) {
                // Добавляем буст за ключевые слова
                val keywordScore = calculateKeywordScore(queryWords, result.chunk.content)
                newScore = (result.similarity * 0.6f + keywordScore * 0.4f)
            }

            RerankedResult(
                original = result,
                rerankedScore = newScore,
                originalScore = result.similarity,
                wasFiltered = false
            )
        }
    }

    /**
     * Извлечение ключевых слов из текста.
     */
    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf(
            "и", "в", "на", "с", "по", "для", "что", "как", "это", "из", "от", "к", "а", "о", "у",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "have", "has",
            "had", "do", "does", "did", "will", "would", "could", "should", "may", "might",
            "to", "of", "in", "for", "on", "with", "at", "by", "from", "as", "into", "through"
        )

        return text.lowercase()
            .replace(Regex("[^a-zа-яё0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }

    /**
     * Вычисление скора на основе ключевых слов.
     */
    private fun calculateKeywordScore(queryWords: Set<String>, content: String): Float {
        if (queryWords.isEmpty()) return 0f

        val contentWords = extractKeywords(content)
        val matches = queryWords.count { it in contentWords }
        val exactMatches = queryWords.count { content.lowercase().contains(it) }

        // Комбинируем точные совпадения и совпадения слов
        return ((matches.toFloat() / queryWords.size) * 0.6f +
                (exactMatches.toFloat() / queryWords.size) * 0.4f).coerceIn(0f, 1f)
    }

    /**
     * Косинусное сходство.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    /**
     * Проверка доступности LLM модели.
     */
    suspend fun isLlmModelAvailable(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/api/tags")
            val body: String = response.body()
            body.contains(llmModel)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Запрос к Ollama generate API.
 */
@Serializable
private data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

/**
 * Ответ от Ollama generate API.
 */
@Serializable
private data class OllamaGenerateResponse(
    val model: String,
    val response: String,
    @SerialName("done") val isDone: Boolean = true
)
