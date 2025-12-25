package org.example.data.rag

import java.io.File

/**
 * Статус индексации.
 */
data class IndexingStatus(
    val totalFiles: Int,
    val processedFiles: Int,
    val totalChunks: Int,
    val processedChunks: Int,
    val currentFile: String? = null
)

/**
 * Результат RAG-поиска для использования в контексте LLM.
 */
data class RagContext(
    val query: String,
    val results: List<SearchResult>,
    val formattedContext: String  // Готовый текст для вставки в промпт
)

/**
 * Результат RAG-поиска с реранкингом.
 */
data class RagContextWithReranking(
    val query: String,
    val originalResults: List<SearchResult>,      // Исходные результаты (до реранкинга)
    val rerankedResults: List<RerankedResult>,    // Результаты после реранкинга
    val finalResults: List<SearchResult>,         // Финальные отфильтрованные результаты
    val formattedContext: String,                 // Готовый текст для вставки в промпт
    val wasReranked: Boolean,                     // Был ли применён реранкинг
    val filteredCount: Int                        // Сколько результатов отфильтровано
)

/**
 * Главный сервис RAG (Retrieval-Augmented Generation).
 * Объединяет индексацию документов и поиск по ним.
 * Поддерживает реранкинг и фильтрацию результатов.
 */
class RagService(
    private val embeddingClient: OllamaEmbeddingClient,
    private val vectorStore: VectorStore,
    private val chunkingService: ChunkingService = ChunkingService(),
    private val ragDirectory: File = File("rag_files"),
    private val rerankerService: RerankerService? = null
) {
    companion object {
        private const val EMBEDDING_MODEL = "mxbai-embed-large"
        private const val META_LAST_INDEX_TIME = "last_index_time"
        private const val META_INDEXED_FILES_COUNT = "indexed_files_count"
        private const val META_TOTAL_CHUNKS = "total_chunks"
    }

    /**
     * Проверить готовность системы (Ollama запущена, модель доступна).
     */
    suspend fun checkReadiness(): ReadinessResult {
        if (!embeddingClient.isAvailable()) {
            return ReadinessResult.OllamaNotRunning
        }
        if (!embeddingClient.isModelAvailable()) {
            return ReadinessResult.ModelNotFound(EMBEDDING_MODEL)
        }
        return ReadinessResult.Ready
    }

    /**
     * Индексация всех документов из директории rag_files.
     * @param forceReindex - переиндексировать даже уже проиндексированные файлы
     * @param onProgress - callback для отображения прогресса
     */
    suspend fun indexDocuments(
        forceReindex: Boolean = false,
        onProgress: (IndexingStatus) -> Unit = {}
    ): IndexingResult {
        // Проверяем готовность
        val readiness = checkReadiness()
        if (readiness !is ReadinessResult.Ready) {
            return IndexingResult.NotReady(readiness)
        }

        if (!ragDirectory.exists() || !ragDirectory.isDirectory) {
            return IndexingResult.Error("Директория ${ragDirectory.absolutePath} не существует")
        }

        val files = ragDirectory.listFiles { f -> f.extension == "txt" }?.toList() ?: emptyList()
        if (files.isEmpty()) {
            return IndexingResult.Error("Нет .txt файлов в ${ragDirectory.absolutePath}")
        }

        // Если forceReindex, очищаем хранилище
        if (forceReindex) {
            vectorStore.clearAll()
        }

        var totalChunksProcessed = 0
        var filesProcessed = 0
        var skippedFiles = 0

        for ((index, file) in files.withIndex()) {
            // Пропускаем уже проиндексированные файлы
            if (!forceReindex && vectorStore.isFileIndexed(file.name)) {
                skippedFiles++
                filesProcessed++
                continue
            }

            onProgress(IndexingStatus(
                totalFiles = files.size,
                processedFiles = filesProcessed,
                totalChunks = totalChunksProcessed,
                processedChunks = totalChunksProcessed,
                currentFile = file.name
            ))

            try {
                // Разбиваем файл на чанки
                val chunks = chunkingService.chunkFile(file)
                if (chunks.isEmpty()) continue

                // Генерируем эмбеддинги батчами
                val texts = chunks.map { it.content }
                val embeddings = embeddingClient.embedBatch(texts) { processed, total ->
                    onProgress(IndexingStatus(
                        totalFiles = files.size,
                        processedFiles = filesProcessed,
                        totalChunks = totalChunksProcessed + total,
                        processedChunks = totalChunksProcessed + processed,
                        currentFile = file.name
                    ))
                }

                // Удаляем старые чанки этого файла (если есть) и сохраняем новые
                vectorStore.deleteFileChunks(file.name)
                vectorStore.saveChunks(chunks, embeddings, EMBEDDING_MODEL)

                totalChunksProcessed += chunks.size
                filesProcessed++

            } catch (e: Exception) {
                return IndexingResult.Error("Ошибка при индексации ${file.name}: ${e.message}")
            }
        }

        // Сохраняем метаданные
        vectorStore.saveMetadata(META_LAST_INDEX_TIME, System.currentTimeMillis().toString())
        vectorStore.saveMetadata(META_INDEXED_FILES_COUNT, filesProcessed.toString())
        vectorStore.saveMetadata(META_TOTAL_CHUNKS, vectorStore.getChunkCount().toString())

        return IndexingResult.Success(
            filesProcessed = filesProcessed,
            filesSkipped = skippedFiles,
            chunksCreated = totalChunksProcessed
        )
    }

    /**
     * Поиск релевантных документов по запросу.
     */
    suspend fun search(
        query: String,
        topK: Int = 5,
        minSimilarity: Float = 0.3f
    ): RagContext {
        // Генерируем эмбеддинг запроса
        val queryEmbedding = embeddingClient.embed(query)

        // Ищем похожие чанки
        val results = vectorStore.search(queryEmbedding, topK, minSimilarity)

        // Формируем контекст для LLM
        val formattedContext = formatContextForLlm(results)

        return RagContext(query, results, formattedContext)
    }

    /**
     * Поиск без генерации эмбеддинга (использует уже готовый индекс).
     * Быстрый вариант если Ollama недоступна, но индекс уже создан.
     */
    fun searchOffline(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<SearchResult> {
        return vectorStore.search(queryEmbedding, topK, minSimilarity)
    }

    /**
     * Поиск с реранкингом и фильтрацией.
     * Двухэтапный процесс:
     * 1. Первичный поиск по cosine similarity (широкий охват)
     * 2. Реранкинг и фильтрация по порогу (точность)
     */
    suspend fun searchWithReranking(
        query: String,
        topK: Int = 5,
        initialTopK: Int = 10,
        minSimilarity: Float = 0.25f,
        rerankerConfig: RerankerConfig = RerankerConfig()
    ): RagContextWithReranking {
        // Этап 1: Широкий первичный поиск
        val queryEmbedding = embeddingClient.embed(query)
        val initialResults = vectorStore.search(queryEmbedding, initialTopK, minSimilarity)

        // Если реранкер не настроен, возвращаем без реранкинга
        if (rerankerService == null) {
            return RagContextWithReranking(
                query = query,
                originalResults = initialResults,
                rerankedResults = emptyList(),
                finalResults = initialResults.take(topK),
                formattedContext = formatContextForLlm(initialResults.take(topK)),
                wasReranked = false,
                filteredCount = 0
            )
        }

        // Этап 2: Реранкинг
        val rerankedResults = rerankerService.rerank(query, initialResults, rerankerConfig)

        // Фильтрация по порогу и ограничение topK
        val filteredResults = rerankerService.filterByThreshold(rerankedResults, rerankerConfig.threshold)
        val finalResults = filteredResults.take(topK).map { it.original }

        return RagContextWithReranking(
            query = query,
            originalResults = initialResults,
            rerankedResults = rerankedResults,
            finalResults = finalResults,
            formattedContext = formatContextForReranked(filteredResults.take(topK)),
            wasReranked = true,
            filteredCount = rerankedResults.size - filteredResults.size
        )
    }

    /**
     * Сравнение результатов с реранкингом и без.
     * Полезно для демонстрации эффективности реранкинга.
     */
    suspend fun compareResults(
        query: String,
        topK: Int = 5,
        rerankerConfig: RerankerConfig = RerankerConfig()
    ): ComparisonResult? {
        if (rerankerService == null) return null

        val queryEmbedding = embeddingClient.embed(query)
        val results = vectorStore.search(queryEmbedding, rerankerConfig.maxCandidates, 0.25f)

        return rerankerService.compare(query, results, rerankerConfig)
    }

    /**
     * Получить статистику индекса.
     */
    fun getIndexStats(): IndexStats {
        val chunkCount = vectorStore.getChunkCount()
        val files = vectorStore.getIndexedFiles()
        val lastIndexTime = vectorStore.getMetadata(META_LAST_INDEX_TIME)?.toLongOrNull()

        return IndexStats(
            totalChunks = chunkCount,
            indexedFiles = files,
            lastIndexTime = lastIndexTime
        )
    }

    /**
     * Проверка доступности реранкера.
     */
    fun hasReranker(): Boolean = rerankerService != null

    /**
     * Форматирование реранкнутых результатов в контекст для LLM.
     * Использует XML-теги для чёткой разметки источников.
     */
    private fun formatContextForReranked(results: List<RerankedResult>): String {
        if (results.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("<rag_context>")
        sb.appendLine("Ниже представлена информация из локальной базы документов.")
        sb.appendLine("ВАЖНО: При использовании этой информации укажи источник в формате [RAG: имя_файла]")
        sb.appendLine()

        results.forEachIndexed { index, result ->
            val rerankedScore = "%.0f%%".format(result.rerankedScore * 100)
            sb.appendLine("<document source=\"${result.original.chunk.sourceFile}\" relevance=\"$rerankedScore\">")
            sb.appendLine(result.original.chunk.content.trim())
            sb.appendLine("</document>")
            sb.appendLine()
        }

        sb.appendLine("</rag_context>")
        return sb.toString()
    }

    /**
     * Форматирование результатов поиска в контекст для LLM.
     * Использует XML-теги для чёткой разметки источников.
     */
    private fun formatContextForLlm(results: List<SearchResult>): String {
        if (results.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("<rag_context>")
        sb.appendLine("Ниже представлена информация из локальной базы документов.")
        sb.appendLine("ВАЖНО: При использовании этой информации укажи источник в формате [RAG: имя_файла]")
        sb.appendLine()

        results.forEachIndexed { index, result ->
            val similarity = "%.0f%%".format(result.similarity * 100)
            sb.appendLine("<document source=\"${result.chunk.sourceFile}\" relevance=\"$similarity\">")
            sb.appendLine(result.chunk.content.trim())
            sb.appendLine("</document>")
            sb.appendLine()
        }

        sb.appendLine("</rag_context>")
        return sb.toString()
    }
}

/**
 * Результат проверки готовности.
 */
sealed class ReadinessResult {
    data object Ready : ReadinessResult()
    data object OllamaNotRunning : ReadinessResult()
    data class ModelNotFound(val model: String) : ReadinessResult()
}

/**
 * Результат индексации.
 */
sealed class IndexingResult {
    data class Success(
        val filesProcessed: Int,
        val filesSkipped: Int,
        val chunksCreated: Int
    ) : IndexingResult()

    data class NotReady(val reason: ReadinessResult) : IndexingResult()
    data class Error(val message: String) : IndexingResult()
}

/**
 * Статистика индекса.
 */
data class IndexStats(
    val totalChunks: Long,
    val indexedFiles: List<String>,
    val lastIndexTime: Long?
)