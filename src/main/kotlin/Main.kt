package org.example

import kotlinx.coroutines.runBlocking
import org.example.app.AppConfig
import org.example.app.AppInitializer
import org.example.app.ChatLoop
import org.example.data.api.OllamaClient
import org.example.data.api.VpsLlmClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.MultiMcpClient
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.MemoryRepository
import org.example.data.rag.ChunkingService
import org.example.data.rag.OllamaEmbeddingClient
import org.example.data.rag.RagService
import org.example.data.rag.RerankerService
import org.example.data.rag.VectorStore
import org.example.presentation.ConsoleInput
import org.example.presentation.LlmProviderSelector
import org.example.utils.BuildInfo
import java.io.File

fun main() = runBlocking {
    println("Build time: ${BuildInfo.BUILD_TIME}")
    println()

    DatabaseConfig.init()
    val memoryRepository = MemoryRepository()

    ConsoleInput().use { console ->
        // Interactive LLM provider selection
        val selectedClient = LlmProviderSelector.selectProvider()
        val providerName = LlmProviderSelector.getProviderName(selectedClient)

        // For Ollama, API key is optional
        val anthropicKey = if (selectedClient is OllamaClient) {
            System.getenv("ANTHROPIC_API_KEY")  // Optional for Ollama
        } else {
            AppInitializer.resolveApiKey(
                console, "ANTHROPIC_API_KEY", "Anthropic"
            ) ?: return@runBlocking
        }

        val json = AppConfig.buildJson()
        val client = AppConfig.buildHttpClient(json)

        // Classpath для динамического подключения MCP серверов
        val classpath = System.getProperty("java.class.path")

        // Подключаемся ко всем MCP серверам (без Wikipedia по умолчанию)
        val multiMcpClient = connectAllMcpServers()

        // Инициализация RAG
        val ragService = initializeRag(client, json)

        try {
            val useCases = if (selectedClient is OllamaClient || selectedClient is VpsLlmClient) {
                // For Ollama/VPS: use selected client for all operations
                AppInitializer.buildUseCasesWithCustomClient(
                    selectedClient,
                    multiMcpClient = multiMcpClient
                )
            } else {
                // For Claude: use existing logic
                AppInitializer.buildUseCases(
                    client, json, anthropicKey!!, null,
                    multiMcpClient = multiMcpClient
                )
            }

            // Show selected LLM provider
            println("Active LLM: $providerName")
            if (multiMcpClient?.isConnected == true) {
                println("MCP servers: connected")
            }
            println()

            ChatLoop(
                console = console,
                useCases = useCases,
                memoryRepository = memoryRepository,
                ragService = ragService,
                multiMcpClient = multiMcpClient,
                classpath = classpath
            ).run()
        } finally {
            multiMcpClient?.disconnectAll()
            client.close()
        }
    }
}

/**
 * Инициализация RAG сервиса с поддержкой реранкинга.
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

    // Создаём реранкер для улучшения качества поиска
    val rerankerService = RerankerService(
        httpClient = httpClient,
        json = json,
        embeddingClient = embeddingClient
    )

    val ragService = RagService(
        embeddingClient = embeddingClient,
        vectorStore = vectorStore,
        chunkingService = chunkingService,
        ragDirectory = ragDirectory,
        projectRoot = File(System.getProperty("user.dir")),
        rerankerService = rerankerService
    )

    // Показываем статус RAG
    val stats = ragService.getIndexStats()
    if (stats.totalChunks > 0) {
        println("RAG инициализирован: ${stats.totalChunks} чанков из ${stats.indexedFiles.size} файлов")
        println("  Реранкинг: включён (методы: cross, llm, keyword)")
    } else {
        println("RAG инициализирован (индекс пуст, используйте /rag index)")
        println("  Реранкинг: доступен")
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