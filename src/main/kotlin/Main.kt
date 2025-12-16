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

        val json = AppConfig.buildJson()
        val client = AppConfig.buildHttpClient(json)

        // Пытаемся подключиться к Weather MCP серверу
        val mcpClient = tryConnectWeatherMcp()

        try {
            val useCases = AppInitializer.buildUseCases(client, json, anthropicKey, openRouterKey, mcpClient)
            ChatLoop(console, useCases, memoryRepository).run()
        } finally {
            mcpClient?.disconnect()
            client.close()
        }
    }
}

/**
 * Попытка подключения к Weather MCP серверу.
 * Сервер запускается автоматически как дочерний процесс.
 */
private suspend fun tryConnectWeatherMcp(): McpClient? {
    return try {
        // Получаем classpath из системного свойства
        val classpath = System.getProperty("java.class.path") ?: return null

        val mcpJson = McpClientFactory.createJson()
        val config = McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.WeatherMcpServerKt"
            )
        )
        val transport = McpStdioTransport(config, mcpJson)
        val mcpClient = McpClient(transport, mcpJson)

        println("Подключение к Weather MCP серверу...")
        val result = mcpClient.connect()
        println("Подключено к MCP: ${result.serverInfo?.name} v${result.serverInfo?.version}")

        val tools = mcpClient.listTools()
        println("Доступные инструменты: ${tools.map { it.name }}")
        println()

        mcpClient
    } catch (e: Exception) {
        println("Предупреждение: Не удалось подключиться к Weather MCP серверу: ${e.message}")
        println("Инструмент погоды будет недоступен.")
        println()
        null
    }
}
