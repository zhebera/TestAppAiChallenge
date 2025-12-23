package org.example.app.commands

import org.example.data.mcp.McpClientFactory

/**
 * Команды для управления MCP серверами.
 *
 * Доступные команды:
 * - /mcp status    - показать статус серверов
 * - /mcp list      - список доступных серверов
 * - /mcp connect <server> - подключить сервер
 * - /mcp disconnect <server> - отключить сервер
 */
class McpControlCommand : Command {

    override fun matches(input: String): Boolean {
        return input.startsWith("/mcp")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val parts = input.removePrefix("/mcp").trim().split(" ", limit = 2)
        val subCommand = parts.getOrNull(0) ?: ""
        val args = parts.getOrNull(1)?.trim() ?: ""

        when (subCommand.lowercase()) {
            "", "help" -> printHelp()
            "status" -> showStatus(context)
            "list" -> listServers(context)
            "connect" -> connectServer(args, context)
            "disconnect" -> disconnectServer(args, context)
            else -> {
                println("Неизвестная подкоманда: $subCommand")
                printHelp()
            }
        }

        return CommandResult.Continue
    }

    private fun printHelp() {
        println("""
            |MCP (Model Context Protocol) - управление серверами инструментов
            |
            |Команды:
            |  /mcp status          - показать статус подключённых серверов
            |  /mcp list            - список всех доступных серверов
            |  /mcp connect <name>  - подключить сервер (wikipedia, summarizer, filestorage, android)
            |  /mcp disconnect <name> - отключить сервер
            |
            |Примечание:
            |  Wikipedia MCP отключён по умолчанию, т.к. дублирует функционал RAG.
            |  Используйте /rag on для работы с локальной базой знаний.
            |  Или /mcp connect wikipedia для работы с онлайн Wikipedia.
        """.trimMargin())
        println()
    }

    private suspend fun showStatus(context: CommandContext) {
        val client = context.multiMcpClient
        if (client == null) {
            println("MCP клиент не инициализирован.")
            println()
            return
        }

        val servers = client.connectedServers
        if (servers.isEmpty()) {
            println("Нет подключённых MCP серверов.")
            println("Используйте /mcp list для просмотра доступных серверов.")
        } else {
            println("=== Подключённые MCP серверы ===")
            servers.forEach { name ->
                println("  ✓ $name")
            }
            println()

            val tools = client.listAllTools()
            if (tools.isNotEmpty()) {
                println("Доступные инструменты:")
                tools.forEach { tool ->
                    println("  - ${tool.name}: ${tool.description?.take(60) ?: "(без описания)"}")
                }
            }
        }
        println()
    }

    private fun listServers(context: CommandContext) {
        val classpath = context.classpath
        if (classpath == null) {
            println("Classpath не доступен.")
            println()
            return
        }

        val allConfigs = McpClientFactory.getAllAvailableServerConfigs(classpath)
        val connectedServers = context.multiMcpClient?.connectedServers ?: emptyList()

        println("=== Доступные MCP серверы ===")
        println()

        allConfigs.keys.forEach { name ->
            val isConnected = name in connectedServers
            val status = if (isConnected) "✓ подключён" else "○ отключён"
            val note = when (name) {
                "wikipedia" -> " (отключён по умолчанию - дублирует RAG)"
                "summarizer" -> " (сжатие текста)"
                "filestorage" -> " (сохранение в файл)"
                "android" -> " (Android эмулятор)"
                else -> ""
            }
            println("  [$status] $name$note")
        }
        println()
        println("Используйте /mcp connect <name> для подключения сервера")
        println()
    }

    private suspend fun connectServer(serverName: String, context: CommandContext) {
        if (serverName.isBlank()) {
            println("Укажите имя сервера: /mcp connect <name>")
            println("Доступные: wikipedia, summarizer, filestorage, android")
            println()
            return
        }

        val client = context.multiMcpClient
        val classpath = context.classpath

        if (client == null || classpath == null) {
            println("MCP клиент не инициализирован.")
            println()
            return
        }

        if (serverName in client.connectedServers) {
            println("Сервер '$serverName' уже подключён.")
            println()
            return
        }

        val allConfigs = McpClientFactory.getAllAvailableServerConfigs(classpath)
        val config = allConfigs[serverName]

        if (config == null) {
            println("Неизвестный сервер: $serverName")
            println("Доступные: ${allConfigs.keys.joinToString(", ")}")
            println()
            return
        }

        println("Подключение к $serverName...")
        val result = client.addServer(serverName, config)

        if (result.success) {
            println("✓ $serverName подключён: ${result.serverInfo}")
            println("  Инструменты: ${result.tools.joinToString(", ")}")
        } else {
            println("✗ Ошибка подключения: ${result.error}")
        }
        println()
    }

    private fun disconnectServer(serverName: String, context: CommandContext) {
        if (serverName.isBlank()) {
            println("Укажите имя сервера: /mcp disconnect <name>")
            println()
            return
        }

        val client = context.multiMcpClient
        if (client == null) {
            println("MCP клиент не инициализирован.")
            println()
            return
        }

        if (serverName !in client.connectedServers) {
            println("Сервер '$serverName' не подключён.")
            println()
            return
        }

        client.disconnect(serverName)
        println("✓ Сервер '$serverName' отключён.")
        println()
    }
}