package org.example.app.commands

import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.McpException

class McpCommand : Command {
    private var mcpClient: McpClient? = null

    override fun matches(input: String): Boolean =
        input.startsWith("/mcp", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val parts = input.trim().split("\\s+".toRegex())
        val subCommand = parts.getOrNull(1)?.lowercase() ?: "help"

        when (subCommand) {
            "connect", "github" -> {
                val token = parts.getOrNull(2)
                connect(token)
            }
            "tools" -> listTools()
            "call" -> {
                val toolName = parts.getOrNull(2)
                if (toolName == null) {
                    println("Использование: /mcp call <tool_name> [args...]")
                } else {
                    val args = parts.drop(3)
                    callTool(toolName, args)
                }
            }
            "disconnect" -> disconnect()
            "status" -> status()
            else -> printHelp()
        }

        return CommandResult.Continue
    }

    private suspend fun connect(token: String?) {
        println()
        println("─".repeat(50))
        println("Подключение к MCP GitHub Server...")

        try {
            mcpClient?.disconnect()

            if (token != null) {
                println("Используется переданный токен")
                mcpClient = McpClientFactory.createGitHubClient(token = token)
            } else {
                println("Используется GITHUB_PERSONAL_ACCESS_TOKEN из окружения")
                mcpClient = McpClientFactory.createGitHubClient()
            }

            val result = mcpClient!!.connect()

            println()
            println("Подключено!")
            println("Сервер: ${result.serverInfo?.name ?: "unknown"} v${result.serverInfo?.version ?: "?"}")
            println("Протокол: ${result.protocolVersion}")
            println("Capabilities: tools=${result.capabilities.tools != null}")
        } catch (e: McpException) {
            println("Ошибка подключения: ${e.message}")
            mcpClient = null
        } catch (e: Exception) {
            println("Ошибка: ${e.message}")
            e.printStackTrace()
            mcpClient = null
        }
        println("─".repeat(50))
        println()
    }

    private suspend fun listTools() {
        println()
        println("─".repeat(50))

        val client = mcpClient
        if (client == null || !client.isConnected) {
            println("Не подключено к MCP серверу.")
            println("Используйте: /mcp connect")
            println("─".repeat(50))
            println()
            return
        }

        try {
            val tools = client.listTools()
            println("Доступные инструменты GitHub MCP (${tools.size}):")
            println()

            tools.forEachIndexed { index, tool ->
                println("${index + 1}. ${tool.name}")
                tool.description?.let { desc ->
                    println("   Описание: $desc")
                }
                tool.inputSchema?.let { schema ->
                    val properties = schema["properties"]
                    val required = schema["required"]
                    if (properties != null) {
                        println("   Параметры: $properties")
                    }
                    if (required != null) {
                        println("   Обязательные: $required")
                    }
                }
                println()
            }
        } catch (e: McpException) {
            println("Ошибка получения инструментов: ${e.message}")
        }

        println("─".repeat(50))
        println()
    }

    private suspend fun callTool(toolName: String, args: List<String>) {
        println()
        println("─".repeat(50))

        val client = mcpClient
        if (client == null || !client.isConnected) {
            println("Не подключено к MCP серверу.")
            println("Используйте: /mcp connect")
            println("─".repeat(50))
            println()
            return
        }

        try {
            // Parse args as key=value pairs
            val arguments = args.mapNotNull { arg ->
                val parts = arg.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to kotlinx.serialization.json.JsonPrimitive(parts[1])
                } else null
            }.toMap()

            println("Вызов инструмента: $toolName")
            if (arguments.isNotEmpty()) {
                println("Аргументы: $arguments")
            }
            println()

            val result = client.callTool(toolName, arguments)

            if (result.isError == true) {
                println("Ошибка выполнения инструмента:")
            } else {
                println("Результат:")
            }

            result.content.forEach { content ->
                when (content.type) {
                    "text" -> println(content.text ?: "(пусто)")
                    "blob" -> println("[Binary data: ${content.mimeType ?: "unknown type"}]")
                    else -> println("[${content.type}]: ${content.text ?: content.data ?: "(нет данных)"}")
                }
            }
        } catch (e: McpException) {
            println("Ошибка вызова инструмента: ${e.message}")
        }

        println("─".repeat(50))
        println()
    }

    private fun disconnect() {
        println()
        println("─".repeat(50))

        mcpClient?.let {
            it.disconnect()
            println("Отключено от MCP GitHub сервера.")
        } ?: println("Нет активного подключения.")

        mcpClient = null
        println("─".repeat(50))
        println()
    }

    private fun status() {
        println()
        println("─".repeat(50))
        println("Статус MCP GitHub:")

        val client = mcpClient
        if (client == null) {
            println("  Подключение: Нет")
        } else {
            println("  Подключение: ${if (client.isConnected) "Активно" else "Разорвано"}")
            client.getServerInfo()?.let { info ->
                println("  Сервер: ${info.name} v${info.version ?: "?"}")
            }
            client.getServerCapabilities()?.let { caps ->
                println("  Tools: ${if (caps.tools != null) "Да" else "Нет"}")
            }
        }

        println("─".repeat(50))
        println()
    }

    private fun printHelp() {
        println()
        println("─".repeat(50))
        println("MCP GitHub команды:")
        println()
        println("Подключение:")
        println("  /mcp connect [token]      - Подключиться к GitHub MCP серверу")
        println("                              Требует GITHUB_PERSONAL_ACCESS_TOKEN")
        println()
        println("Работа с инструментами:")
        println("  /mcp tools                - Показать список доступных инструментов")
        println("  /mcp call <tool> [args]   - Вызвать инструмент (args: key=value)")
        println()
        println("Управление:")
        println("  /mcp status               - Показать статус подключения")
        println("  /mcp disconnect           - Отключиться от сервера")
        println("  /mcp help                 - Показать эту справку")
        println()
        println("Примеры:")
        println("  /mcp connect")
        println("  /mcp tools")
        println("  /mcp call search_repositories query=kotlin")
        println("  /mcp call get_file_contents owner=anthropics repo=claude-code path=README.md")
        println("─".repeat(50))
        println()
    }
}