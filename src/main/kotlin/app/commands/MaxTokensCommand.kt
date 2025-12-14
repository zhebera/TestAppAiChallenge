package org.example.app.commands

class MaxTokensCommand : Command {
    override fun matches(input: String): Boolean =
        input.startsWith("/maxTokens", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val parts = input.split(" ", limit = 2)
        if (parts.size == 2) {
            val value = parts[1].toIntOrNull()
            if (value != null && value > 0) {
                context.state.currentMaxTokens = value
                println("Max tokens установлен: $value")
                println("(Установите маленькое значение, например 50, чтобы увидеть stop_reason='max_tokens')")
                println()
            } else {
                println("Некорректное значение. Введите положительное число")
                println()
            }
        } else {
            println("Текущий max_tokens: ${context.state.currentMaxTokens}")
            println("Использование: /maxTokens <число>")
            println("Пример: /maxTokens 100  - маленький лимит (ответ будет обрезан)")
            println("Пример: /maxTokens 4096 - большой лимит")
            println()
        }
        return CommandResult.Continue
    }
}
