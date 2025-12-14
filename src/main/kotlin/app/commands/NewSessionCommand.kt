package org.example.app.commands

class NewSessionCommand : Command {
    override fun matches(input: String): Boolean =
        input.equals("/new", ignoreCase = true) || input.equals("/clear", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val oldStats = context.chatHistory.getStats()
        context.chatHistory.clearAndNewSession()
        println()
        println("Создана новая сессия.")
        if (oldStats.totalProcessedMessages > 0) {
            println("Предыдущая сессия сохранена в базе данных.")
            println("  Было сообщений: ${oldStats.currentMessageCount}")
            if (oldStats.compressedMessageCount > 0) {
                println("  Сжатых: ${oldStats.compressedMessageCount}")
            }
        }
        println("Начинаем новый диалог.")
        println()
        return CommandResult.Continue
    }
}
