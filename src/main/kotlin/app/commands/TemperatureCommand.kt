package org.example.app.commands

class TemperatureCommand : Command {
    override fun matches(input: String): Boolean =
        input.startsWith("/temperature", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val parts = input.split(" ", limit = 2)
        if (parts.size == 2) {
            val value = parts[1].toDoubleOrNull()
            if (value != null && value in 0.0..1.0) {
                context.state.currentTemperature = value
                println("Temperature установлен: $value")
                println()
            } else {
                println("Некорректное значение. Введите число от 0.0 до 1.0")
                println()
            }
        } else {
            println("Текущий temperature: ${context.state.currentTemperature ?: "не установлен (по умолчанию)"}")
            println("Использование: /temperature <значение от 0.0 до 1.0>")
            println("Пример: /temperature 0.7")
            println()
        }
        return CommandResult.Continue
    }
}
