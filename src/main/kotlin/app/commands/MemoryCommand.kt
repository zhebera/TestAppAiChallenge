package org.example.app.commands

import org.example.domain.models.ChatRole
import java.text.SimpleDateFormat
import java.util.Date

class MemoryCommand : Command {
    override fun matches(input: String): Boolean =
        input.startsWith("/memory", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val parts = input.split(" ", limit = 3)
        val subCommand = parts.getOrNull(1)?.lowercase() ?: "help"

        when (subCommand) {
            "show" -> showMemory(context)
            "search" -> searchMemory(parts.getOrNull(2), context)
            "clear" -> clearMemory(context)
            else -> showHelp()
        }
        return CommandResult.Continue
    }

    private fun showMemory(context: CommandContext) {
        println()
        println("─".repeat(50))
        println("Последние сообщения в памяти:")
        println()

        val sid = context.chatHistory.currentSessionId
        if (sid != null) {
            val messages = context.memoryRepository.getRecentMessages(sid, 10)
            if (messages.isEmpty()) {
                println("  (пусто)")
            } else {
                val dateFormat = SimpleDateFormat("dd.MM HH:mm")
                messages.forEach { msg ->
                    val time = dateFormat.format(Date(msg.timestamp))
                    val role = formatRole(msg.role)
                    val preview = msg.content.take(80).replace("\n", " ")
                    val suffix = if (msg.content.length > 80) "..." else ""
                    println("  [$time] $role: $preview$suffix")
                }
            }
        } else {
            println("  Сессия не инициализирована")
        }
        println("─".repeat(50))
        println()
    }

    private fun searchMemory(query: String?, context: CommandContext) {
        if (query.isNullOrBlank()) {
            println("Использование: /memory search <запрос>")
            println("Пример: /memory search kotlin")
            println()
            return
        }

        println()
        println("─".repeat(50))
        println("Поиск: \"$query\"")
        println()

        val results = context.chatHistory.search(query)
        if (results.isEmpty()) {
            println("  Ничего не найдено")
        } else {
            println("  Найдено ${results.size} сообщений:")
            results.take(10).forEach { msg ->
                val role = formatRole(msg.role)
                val preview = msg.content.take(100).replace("\n", " ")
                val suffix = if (msg.content.length > 100) "..." else ""
                println("  $role: $preview$suffix")
                println()
            }
        }
        println("─".repeat(50))
        println()
    }

    private suspend fun clearMemory(context: CommandContext) {
        println()
        print("Вы уверены, что хотите очистить ВСЮ память? (yes/no): ")
        val confirm = context.console.readLine("")?.trim()?.lowercase()
        if (confirm == "yes" || confirm == "y") {
            context.memoryRepository.clearAll()
            context.chatHistory.clear()
            context.chatHistory.initSession(createNew = true)
            println("Вся память очищена. Создана новая сессия.")
        } else {
            println("Отменено.")
        }
        println()
    }

    private fun showHelp() {
        println()
        println("Команды /memory:")
        println("  /memory show           - показать последние 10 сообщений")
        println("  /memory search <текст> - поиск по истории сообщений")
        println("  /memory clear          - очистить всю память (требует подтверждения)")
        println()
    }

    private fun formatRole(role: ChatRole): String = when (role) {
        ChatRole.USER -> "Вы"
        ChatRole.ASSISTANT -> "AI"
        ChatRole.SYSTEM -> "SYS"
    }
}
