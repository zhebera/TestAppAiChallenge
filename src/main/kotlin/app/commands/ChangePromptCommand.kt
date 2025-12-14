package org.example.app.commands

import org.example.utils.SYSTEM_FORMAT_PROMPT
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC
import org.example.utils.SYSTEM_FORMAT_PROMPT_PIRATE
import org.example.utils.SYSTEM_FORMAT_PROMPT_TOKAR

class ChangePromptCommand : Command {
    override fun matches(input: String): Boolean =
        input.equals("/changePrompt", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        println()
        println("Выберите новый system prompt:")
        println("1 - Обычный ИИ помощник")
        println("2 - Логические задачи")
        println("3 - Токарь")
        println("4 - Пират 18 века")
        print("Ваш выбор (1/2/3/4): ")

        val choice = context.console.readLine("")?.trim()

        context.state.currentSystemPrompt = when (choice) {
            "1" -> SYSTEM_FORMAT_PROMPT
            "2" -> SYSTEM_FORMAT_PROMPT_LOGIC
            "3" -> SYSTEM_FORMAT_PROMPT_TOKAR
            "4" -> SYSTEM_FORMAT_PROMPT_PIRATE
            else -> {
                println("Неизвестный выбор, оставляю прежний system prompt.")
                context.state.currentSystemPrompt
            }
        }

        val role = when (context.state.currentSystemPrompt) {
            SYSTEM_FORMAT_PROMPT -> "Обычный ИИ помощник"
            SYSTEM_FORMAT_PROMPT_LOGIC -> "помощник по решению логических, математических и головоломных задач"
            SYSTEM_FORMAT_PROMPT_TOKAR -> "опытный токарь с 25-летним стажем, мастер по металлообработке"
            SYSTEM_FORMAT_PROMPT_PIRATE -> "пират 18 века"
            else -> ""
        }

        if (role.isNotEmpty()) {
            println("System prompt обновлён на $role.")
            println()
        }
        return CommandResult.Continue
    }
}
