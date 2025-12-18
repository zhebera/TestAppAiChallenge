package org.example.data.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Клиент для работы с несколькими MCP серверами одновременно.
 * Объединяет инструменты от всех подключенных серверов.
 */
class MultiMcpClient(
    private val json: Json = McpClientFactory.createJson()
) {
    private val clients = mutableMapOf<String, McpClient>()
    private val toolToServer = mutableMapOf<String, String>()

    val isConnected: Boolean
        get() = clients.any { it.value.isConnected }

    val connectedServers: List<String>
        get() = clients.filter { it.value.isConnected }.keys.toList()

    /**
     * Добавить и подключить MCP сервер
     */
    suspend fun addServer(name: String, config: McpServerConfig): ServerConnectResult {
        return try {
            val transport = McpStdioTransport(config, json)
            val client = McpClient(transport, json)

            val initResult = client.connect()
            clients[name] = client

            // Регистрируем инструменты этого сервера
            val tools = client.listTools()
            tools.forEach { tool ->
                toolToServer[tool.name] = name
            }

            ServerConnectResult(
                success = true,
                serverName = name,
                serverInfo = "${initResult.serverInfo?.name} v${initResult.serverInfo?.version}",
                toolCount = tools.size,
                tools = tools.map { it.name }
            )
        } catch (e: Exception) {
            ServerConnectResult(
                success = false,
                serverName = name,
                error = e.message
            )
        }
    }

    /**
     * Получить список всех инструментов от всех серверов
     */
    suspend fun listAllTools(): List<McpTool> {
        return clients.values
            .filter { it.isConnected }
            .flatMap {
                try {
                    it.listTools()
                } catch (e: Exception) {
                    emptyList()
                }
            }
    }

    /**
     * Вызвать инструмент (автоматически находит нужный сервер)
     */
    suspend fun callTool(name: String, arguments: Map<String, JsonElement> = emptyMap()): CallToolResult {
        val serverName = toolToServer[name]
            ?: throw McpException("Tool '$name' not found in any connected server")

        val client = clients[serverName]
            ?: throw McpException("Server '$serverName' not found")

        if (!client.isConnected) {
            throw McpException("Server '$serverName' is not connected")
        }

        return client.callTool(name, arguments)
    }

    /**
     * Отключить все серверы
     */
    fun disconnectAll() {
        clients.values.forEach {
            try {
                it.disconnect()
            } catch (_: Exception) {
                // Игнорируем ошибки отключения
            }
        }
        clients.clear()
        toolToServer.clear()
    }

    /**
     * Отключить конкретный сервер
     */
    fun disconnect(serverName: String) {
        clients[serverName]?.let { client ->
            client.disconnect()
            // Удаляем инструменты этого сервера
            toolToServer.entries.removeIf { it.value == serverName }
        }
        clients.remove(serverName)
    }
}

data class ServerConnectResult(
    val success: Boolean,
    val serverName: String,
    val serverInfo: String? = null,
    val toolCount: Int = 0,
    val tools: List<String> = emptyList(),
    val error: String? = null
)