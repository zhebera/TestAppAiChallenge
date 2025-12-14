package org.example.app.commands

class StatsCommand : Command {
    override fun matches(input: String): Boolean =
        input.equals("/stats", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val stats = context.chatHistory.getStats()
        println()
        println("─".repeat(50))
        println("Статистика истории диалога:")
        println("  Текущих сообщений в памяти: ${stats.currentMessageCount}")
        println("  Сжатых сообщений:           ${stats.compressedMessageCount}")
        println("  Всего обработано:           ${stats.totalProcessedMessages}")
        println("  Есть summary:               ${if (stats.hasSummary) "Да" else "Нет"}")
        if (stats.hasSummary) {
            println("  Размер summary:             ${stats.summaryLength} символов")
        }
        if (stats.hasSummary) {
            println("   Summary text: ${stats.summaryText}")
        }
        println("─".repeat(50))
        println()
        return CommandResult.Continue
    }
}
