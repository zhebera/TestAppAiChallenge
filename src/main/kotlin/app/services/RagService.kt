package app.services

import app.config.AppConfig
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class RagService(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(RagService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun autoIndexProject() {
        scope.launch {
            try {
                logger.info("Starting automatic project indexing...")
                indexProject()
                logger.info("Automatic project indexing completed successfully")
            } catch (e: Exception) {
                logger.error("Failed to auto-index project", e)
            }
        }
    }

    suspend fun indexProject() {
        val projectRoot = File(".").canonicalPath
        logger.info("Indexing project files from: $projectRoot")
        
        val allowedExtensions = setOf("kt", "java", "md", "txt", "yml", "yaml", "json", "xml", "properties")
        val excludeDirs = setOf(".git", ".gradle", "build", "target", ".idea", "node_modules")
        
        val files = Files.walk(Path.of(projectRoot))
            .filter { it.isRegularFile() }
            .filter { it.extension.lowercase() in allowedExtensions }
            .filter { path -> excludeDirs.none { exclude -> path.toString().contains("/$exclude/") } }
            .toList()
        
        logger.info("Found ${files.size} files to index")
        
        files.forEach { file ->
            try {
                val content = file.readText()
                if (content.isNotBlank() && content.length > 50) {
                    val embedding = embeddingService.createEmbedding(content)
                    vectorStore.addDocument(file.toString(), content, embedding)
                }
            } catch (e: Exception) {
                logger.warn("Failed to index file: $file", e)
            }
        }
        
        logger.info("Project indexing completed")
    }

    suspend fun indexFiles(directory: String = "rag_files") {
        val ragDir = File(directory)
        if (!ragDir.exists()) {
            logger.warn("Directory $directory does not exist")
            return
        }
        
        val files = ragDir.listFiles { file -> file.extension.lowercase() == "txt" } ?: emptyArray()
        logger.info("Indexing ${files.size} files from $directory")
        
        files.forEach { file ->
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val embedding = embeddingService.createEmbedding(content)
                    vectorStore.addDocument(file.name, content, embedding)
                }
            } catch (e: Exception) {
                logger.warn("Failed to index file: ${file.name}", e)
            }
        }
        
        logger.info("File indexing completed")
    }

    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        val queryEmbedding = embeddingService.createEmbedding(query)
        return vectorStore.search(queryEmbedding, limit)
    }

    fun getStatus(): IndexStatus {
        return IndexStatus(
            documentCount = vectorStore.getDocumentCount(),
            isReady = vectorStore.getDocumentCount() > 0
        )
    }

    suspend fun reindex() {
        vectorStore.clear()
        indexFiles()
        indexProject()
    }

    fun shutdown() {
        scope.cancel()
    }
}

data class SearchResult(
    val id: String,
    val content: String,
    val score: Double
)

data class IndexStatus(
    val documentCount: Int,
    val isReady: Boolean
)