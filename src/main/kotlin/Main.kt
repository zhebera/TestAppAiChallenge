package org.example

import kotlinx.coroutines.runBlocking
import org.example.app.AppConfig
import org.example.app.AppInitializer
import org.example.app.ChatLoop
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.MultiMcpClient
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.MemoryRepository
import org.example.data.rag.ChunkingService
import org.example.data.rag.OllamaEmbeddingClient
import org.example.data.rag.RagService
import org.example.data.rag.VectorStore
import org.example.presentation.ConsoleInput
import java.io.File

fun main() = runBlocking {
    DatabaseConfig.init()
    val memoryRepository = MemoryRepository()

    ConsoleInput().use { console ->
        val anthropicKey = AppInitializer.resolveApiKey(
            console, "ANTHROPIC_API_KEY", "Anthropic"
        ) ?: return@runBlocking

        val openRouterKey = AppInitializer.resolveApiKey(
            console, "OPENROUTER_API_KEY", "OpenRouter (для сжатия истории)"
        )

        val json = AppConfig.buildJson()
        val client = AppConfig.buildHttpClient(json)

        // Подключаемся ко всем MCP серверам
        val multiMcpClient = connectAllMcpServers()

        // Инициализация RAG
        val ragService = initializeRag(client, json)

        try {
            val useCases = AppInitializer.buildUseCases(
                client, json, anthropicKey, openRouterKey,
                multiMcpClient = multiMcpClient
            )
            ChatLoop(console, useCases, memoryRepository, ragService).run()
        } finally {
            multiMcpClient?.disconnectAll()
            client.close()
        }
    }
}

/**
 * Инициализация RAG сервиса.
 * RAG работает независимо от основного LLM клиента.
 */
private fun initializeRag(
    httpClient: io.ktor.client.HttpClient,
    json: kotlinx.serialization.json.Json
): RagService {
    val embeddingClient = OllamaEmbeddingClient(httpClient, json)
    val vectorStore = VectorStore()
    val chunkingService = ChunkingService()
    val ragDirectory = File("rag_files")

    val ragService = RagService(embeddingClient, vectorStore, chunkingService, ragDirectory)

    // Показываем статус RAG
    val stats = ragService.getIndexStats()
    if (stats.totalChunks > 0) {
        println("RAG инициализирован: ${stats.totalChunks} чанков из ${stats.indexedFiles.size} файлов")
    } else {
        println("RAG инициализирован (индекс пуст, используйте /rag index)")
    }

    return ragService
}

/**
 * Подключение ко всем локальным MCP серверам.
 * Серверы запускаются автоматически как дочерние процессы.
 */
private suspend fun connectAllMcpServers(): MultiMcpClient? {
    val classpath = System.getProperty("java.class.path") ?: return null

    val multiClient = MultiMcpClient()
    val configs = McpClientFactory.getAllLocalServerConfigs(classpath)

    println("Подключение к MCP серверам...")
    println()

    var connectedCount = 0
    val allTools = mutableListOf<String>()

    for ((name, config) in configs) {
        val result = multiClient.addServer(name, config)
        if (result.success) {
            println("  ✓ ${result.serverName}: ${result.serverInfo} (${result.toolCount} инструментов)")
            allTools.addAll(result.tools)
            connectedCount++
        } else {
            println("  ✗ ${result.serverName}: ${result.error}")
        }
    }

    println()
    if (connectedCount > 0) {
        println("Подключено серверов: $connectedCount из ${configs.size}")
        println("Доступные инструменты: $allTools")
        println()
        return multiClient
    } else {
        println("Не удалось подключиться ни к одному MCP серверу.")
        println("Инструменты будут недоступны.")
        println()
        return null
    }
}