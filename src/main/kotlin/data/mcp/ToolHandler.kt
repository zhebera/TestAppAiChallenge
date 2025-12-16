package org.example.data.mcp

import kotlinx.serialization.json.*
import org.example.data.api.AnthropicContentBlockDto
import org.example.data.api.AnthropicToolDto

/**
 * Обрабатывает вызовы инструментов от Claude API, перенаправляя их на MCP серверы
 */
class ToolHandler(
    private val mcpClient: McpClient?
) {
    /**
     * Получить список доступных инструментов для Claude API
     */
    suspend fun getAvailableTools(): List<AnthropicToolDto> {
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

        if (mcpClient == null || !mcpClient.isConnected) {
            return "Ошибка: MCP клиент не подключен"
        }

        return try {
            val arguments = toolInput.entries.associate { (k, v) -> k to v }
            val result = mcpClient.callTool(toolName, arguments)

            // Извлекаем текстовый контент из результата
            result.content.mapNotNull { content ->
                when (content.type) {
                    "text" -> content.text
                    else -> null
                }
            }.joinToString("\n").ifEmpty { "Инструмент выполнен успешно (нет вывода)" }
        } catch (e: Exception) {
            "Ошибка выполнения инструмента: ${e.message}"
        }
    }
}