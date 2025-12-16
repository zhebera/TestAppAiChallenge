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
     * Execute a tool call and return the result
     */
    suspend fun executeTool(toolUseBlock: AnthropicContentBlockDto): String {
        val toolName = toolUseBlock.name ?: return "Error: Tool name is missing"
        val toolInput = toolUseBlock.input ?: JsonObject(emptyMap())

        if (mcpClient == null || !mcpClient.isConnected) {
            return "Error: MCP client is not connected"
        }

        return try {
            val arguments = toolInput.entries.associate { (k, v) -> k to v }
            val result = mcpClient.callTool(toolName, arguments)

            // Extract text content from result
            result.content.mapNotNull { content ->
                when (content.type) {
                    "text" -> content.text
                    else -> null
                }
            }.joinToString("\n").ifEmpty { "Tool executed successfully (no output)" }
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
        }
    }
}