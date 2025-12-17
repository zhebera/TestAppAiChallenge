package org.example

import kotlinx.coroutines.runBlocking
import org.example.app.AppConfig
import org.example.app.AppInitializer
import org.example.app.ChatLoop
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.McpServerConfig
import org.example.data.mcp.McpStdioTransport
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.MemoryRepository
import org.example.data.scheduler.NewsScheduler
import org.example.presentation.ConsoleInput

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

        // Создаем и запускаем планировщик футбольных новостей (после получения API ключа)
        val newsScheduler = NewsScheduler(
            apiKey = anthropicKey,
            intervalMs = 60_000L  // Проверка каждую минуту
        )
        newsScheduler.start()

        val json = AppConfig.buildJson()
        val client = AppConfig.buildHttpClient(json)

        // Пытаемся подключиться к Football News MCP серверу
        val mcpClient = tryConnectFootballMcp()

        try {
            val useCases = AppInitializer.buildUseCases(client, json, anthropicKey, openRouterKey, mcpClient)
            ChatLoop(console, useCases, memoryRepository).run()
        } finally {
            mcpClient?.disconnect()
            newsScheduler.stop()
            client.close()
        }
    }
}

/**
 * Попытка подключения к Football News MCP серверу.
 * Сервер запускается автоматически как дочерний процесс.
 */
private suspend fun tryConnectFootballMcp(): McpClient? {
    return try {
        // Получаем classpath из системного свойства
        val classpath = System.getProperty("java.class.path") ?: return null

        val mcpJson = McpClientFactory.createJson()
        val config = McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.FootballMcpServerKt"
            )
        )
        val transport = McpStdioTransport(config, mcpJson)
        val mcpClient = McpClient(transport, mcpJson)

        println("Подключение к Football News MCP серверу...")
        val result = mcpClient.connect()
        println("Подключено к MCP: ${result.serverInfo?.name} v${result.serverInfo?.version}")

        val tools = mcpClient.listTools()
        println("Доступные инструменты: ${tools.map { it.name }}")
        println()

        mcpClient
    } catch (e: Exception) {
        println("Предупреждение: Не удалось подключиться к Football News MCP серверу: ${e.message}")
        println("Инструмент новостей будет недоступен.")
        println()
        null
    }
}
