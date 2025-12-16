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

        // Try to connect to Weather MCP server
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
 * Try to connect to the Weather MCP server.
 * The server must be running separately via: ./gradlew runWeatherMcp
 */
private suspend fun tryConnectWeatherMcp(): McpClient? {
    return try {
        // Get classpath from system property or use default build output
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

        println("Connecting to Weather MCP server...")
        val result = mcpClient.connect()
        println("Connected to MCP: ${result.serverInfo?.name} v${result.serverInfo?.version}")

        val tools = mcpClient.listTools()
        println("Available tools: ${tools.map { it.name }}")
        println()

        mcpClient
    } catch (e: Exception) {
        println("Warning: Could not connect to Weather MCP server: ${e.message}")
        println("Weather tool will not be available.")
        println()
        null
    }
}
