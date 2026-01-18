```kotlin
package org.example.data.rag

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
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
    private val projectRoot: File? = null,
    private val rerankerService: RerankerService? = null
) {
    companion object {
        private const val EMBEDDING_MODEL = "mxbai-embed-large"
        private const val META_LAST_INDEX_TIME = "last_index_time"
        private const val META_INDEXED_FILES_COUNT = "indexed_files_count"
        private const val META_TOTAL_CHUNKS = "total_chunks"
        private val logger = LoggerFactory.getLogger(RagService::class.java)
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
     * Индексация проекта при старте приложения.
     */
    fun indexProject() {
        runBlocking {
            logger.info("Начинаем индексацию проекта при старте приложения...")
            
            val result = indexProjectFiles(forceReindex = false) { status ->
                if (status.currentFile != null) {
                    logger.debug("Индексируем файл: ${status.currentFile} (${status.processedFiles}/${status.totalFiles})")
                }
            }
            
            when (result) {
                is IndexingResult.Success -> {
                    logger.info("Индексация проекта завершена успешно. " +
                               "Обработано файлов: ${result.filesProcessed}, " +
                               "пропущено: ${result.filesSkipped}, " +
                               "создано чанков: ${result.chunksCreated}")
                }
                is IndexingResult.Error -> {
                    logger.warn("Ошибка при индексации проекта: ${result.message}")
                }
                is IndexingResult.NotReady -> {
                    logger.warn("Система не готова для индексации: ${result.readinessResult}")
                }
            }
        }
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
     * Индексация файлов проекта (.kt, .md, .gradle.kts).
     * @param forceReindex - переиндексировать даже уже проиндексированные файлы
     * @param onProgress - callback для отображения прогресса
     */
    suspend fun indexProjectFiles(
        forceReindex: Boolean = false,
        onProgress: (IndexingStatus) -> Unit = {}
    ): IndexingResult {
        // Проверяем готовность
        val readiness = checkReadiness()
        if (readiness !is ReadinessResult.Ready) {
            return IndexingResult.NotReady(readiness)
        }

        if (projectRoot == null) {
            return IndexingResult.Error("Project root не установлен")
        }

        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return IndexingResult.Error("Директория ${projectRoot.absolutePath} не существует")
        }

        // Сканируем файлы проекта
        val scanner = ProjectFileScanner(projectRoot)
        val files = scanner.scanFiles()

        if (files.isEmpty()) {
            return IndexingResult.Error("Нет .kt/.md/.kts файлов в проекте")
        }

        // Если forceReindex, очищаем только файлы проекта (по префиксу)
        if (forceReindex) {
            files.forEach { file ->
                val relativePath = scanner.getRelativePath(file)
                vectorStore.deleteFileChunks(relativePath)
            }
        }

        var totalChunksProcessed = 0
        var filesProcessed = 0
        var skippedFiles = 0

        for ((index, file) in files.withIndex()) {
            val relativePath = scanner.getRelativePath(file)

            // Пропускаем уже проиндексированные файлы
            if (!forceReindex && vectorStore.isFileIndexed(relativePath)) {
                skippedFiles++
                filesProcessed++
                continue
            }

            onProgress(IndexingStatus(
                totalFiles = files.size,
                processedFiles = filesProcessed,
                totalChunks = totalChunksProcessed,
                processedChunks = totalChunksProcessed,
                currentFile = relativePath
            ))

            try {
                // Разбиваем файл на чанки (используем относительный путь)
                val text = file.readText()
                val chunks = chunkingService.chunkText(text, relativePath)
                if (chunks.isEmpty()) continue

                // Генерируем эмбеддинги батчами
                val texts = chunks.map { it.content }
                val embeddings = embeddingClient.embedBatch(texts) { processed, total ->
                    onProgress(IndexingStatus(
                        totalFiles = files.size,
                        processedFiles = filesProcessed,
                        totalChunks = totalChunksProcessed + total,
                        processedChunks = totalChunksProcessed + processed,
                        currentFile = relativePath
                    ))
                }

                // Удаляем старые чанки этого файла (если есть) и сохраняем новые
                vectorStore.deleteFileChunks(relativePath)
                vectorStore.saveChunks(chunks, embeddings, EMBEDDING_MODEL)

                totalChunksProcessed += chunks.size
                filesProcessed++

            } catch (e: Exception) {
                return IndexingResult.Error("Ошибка при индексации $relativePath: ${e.message}")
            }
        }

        // Сохраняем метаданные
        vectorStore.saveMetadata(META_LAST_INDEX_TIME, System.currentTimeMillis().toString())
        vectorStore.saveMetadata("project_indexed_files_count", filesProcessed.toString())
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
        
        // Ищем похожие документы
        val results = vectorStore.search(queryEmbedding, topK, minSimilarity)
        
        // Форматируем контекст
        val formattedContext = formatContext(results)
        
        return RagContext(
            query = query,
            results = results,
            formattedContext = formattedContext
        )
    }

    /**
     * Поиск с реранкингом.
     */
    suspend fun searchWithReranking(
        query: String,
        topK: Int = 5,
        minSimilarity: Float = 0.3f,
        rerankTopK: Int = 3,
        rerankThreshold: Float = 0.5f
    ): RagContextWithReranking {
        // Генерируем эмбеддинг запроса
        val queryEmbedding = embeddingClient.embed(query)
        
        // Ищем похожие документы (больше чем нужно для реранкинга)
        val originalResults = vectorStore.search(queryEmbedding, topK * 2, minSimilarity)
        
        var rerankedResults = emptyList<RerankedResult>()
        var finalResults = originalResults.take(topK)
        var wasReranked = false
        var filteredCount = 0
        
        // Применяем реранкинг если доступен
        if (rerankerService != null && originalResults.isNotEmpty()) {
            rerankedResults = rerankerService.rerank(query, originalResults)
            
            // Фильтруем по порогу и берём топ-K
            val filtered = rerankedResults.filter { it.score >= rerankThreshold }
            finalResults = filtered.take(rerankTopK).map { it.searchResult }
            
            wasReranked = true
            filteredCount = rerankedResults.size - filtered.size
        }
        
        // Форматируем контекст
        val formattedContext = formatContext(finalResults)
        
        return RagContextWithReranking(
            query = query,
            originalResults = originalResults,
            rerankedResults = rerankedResults,
            finalResults = finalResults,
            formattedContext = formattedContext,
            wasReranked = wasReranked,
            filteredCount = filteredCount
        )
    }

    /**
     * Форматирование результатов поиска в контекст для LLM.
     */
    private fun formatContext(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        
        return buildString {
            appendLine("Ниже представлена информация из локальной базы документов.")
            appendLine("ВАЖНО: При использовании этой информации укажи источник в формате [RAG: имя_файла]")
            appendLine()
            
            results.forEachIndexed { index, result ->
                appendLine("<document source=\"${result.fileName}\" relevance=\"${(result.similarity * 100).toInt()}%\">")
                appendLine(result.content.trim())
                appendLine("</document>")
                if (index < results.size - 1) appendLine()
            }
        }
    }

    /**
     * Получить статистику индекса.
     */
    fun getIndexStats(): IndexStats {
        val lastIndexTime = vectorStore.getMetadata