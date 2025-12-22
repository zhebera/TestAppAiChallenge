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
 * Главный сервис RAG (Retrieval-Augmented Generation).
 * Объединяет индексацию документов и поиск по ним.
 */
class RagService(
    private val embeddingClient: OllamaEmbeddingClient,
    private val vectorStore: VectorStore,
    private val chunkingService: ChunkingService = ChunkingService(),
    private val ragDirectory: File = File("rag_files")
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
     * Форматирование результатов поиска в контекст для LLM.
     */
    private fun formatContextForLlm(results: List<SearchResult>): String {
        if (results.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("=== Релевантная информация из базы знаний ===")
        sb.appendLine()

        results.forEachIndexed { index, result ->
            val similarity = "%.1f%%".format(result.similarity * 100)
            sb.appendLine("--- Источник ${index + 1}: ${result.chunk.sourceFile} (релевантность: $similarity) ---")
            sb.appendLine(result.chunk.content)
            sb.appendLine()
        }

        sb.appendLine("=== Конец контекста из базы знаний ===")
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