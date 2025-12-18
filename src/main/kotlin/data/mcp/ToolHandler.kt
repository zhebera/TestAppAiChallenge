package org.example.data.mcp

import kotlinx.serialization.json.*
import org.example.data.api.AnthropicContentBlockDto
import org.example.data.api.AnthropicToolDto

/**
 * Обрабатывает вызовы инструментов от Claude API, перенаправляя их на MCP серверы.
 * Поддерживает работу как с одним MCP клиентом, так и с MultiMcpClient.
 */
class ToolHandler(
    private val mcpClient: McpClient? = null,
    private val multiMcpClient: MultiMcpClient? = null
) {
    /**
     * Получить список доступных инструментов для Claude API
     */
    suspend fun getAvailableTools(): List<AnthropicToolDto> {
        // Приоритет MultiMcpClient
        if (multiMcpClient != null && multiMcpClient.isConnected) {
            return try {
                multiMcpClient.listAllTools().map { mcpTool ->
                    AnthropicToolDto(
                        name = mcpTool.name,
                        description = mcpTool.description,
                        inputSchema = mcpTool.inputSchema ?: buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Fallback на одиночный клиент
        if (mcpClient == null || !mcpClient.isConnected) {
            return emptyList()
        }

        return try {
            mcpClient.listTools().map { mcpTool ->
                AnthropicToolDto(
                    name = mcpTool.name,
                    description = mcpTool.description,
                    inputSchema = mcpTool.inputSchema ?: buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Выполнить вызов инструмента и вернуть результат
     */
    suspend fun executeTool(toolUseBlock: AnthropicContentBlockDto): String {
        val toolName = toolUseBlock.name ?: return "Ошибка: Имя инструмента отсутствует"
        val toolInput = toolUseBlock.input ?: JsonObject(emptyMap())
        val arguments = toolInput.entries.associate { (k, v) -> k to v }

        // Приоритет MultiMcpClient
        if (multiMcpClient != null && multiMcpClient.isConnected) {
            return try {
                val result = multiMcpClient.callTool(toolName, arguments)
                extractTextContent(result)
            } catch (e: Exception) {
                "Ошибка выполнения инструмента: ${e.message}"
            }
        }

        // Fallback на одиночный клиент
        if (mcpClient == null || !mcpClient.isConnected) {
            return "Ошибка: MCP клиент не подключен"
        }

        return try {
            val result = mcpClient.callTool(toolName, arguments)
            extractTextContent(result)
        } catch (e: Exception) {
            "Ошибка выполнения инструмента: ${e.message}"
        }
    }

    private fun extractTextContent(result: CallToolResult): String {
        return result.content.mapNotNull { content ->
            when (content.type) {
                "text" -> content.text
                else -> null
            }
        }.joinToString("\n").ifEmpty { "Инструмент выполнен успешно (нет вывода)" }
    }
}