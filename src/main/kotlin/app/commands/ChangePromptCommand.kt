package org.example.app.commands

import org.example.utils.SYSTEM_FORMAT_PROMPT
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC
import org.example.utils.SYSTEM_FORMAT_PROMPT_PIRATE
import org.example.utils.SYSTEM_FORMAT_PROMPT_TOKAR
import org.example.utils.VPS_SYSTEM_PROMPT_CONCISE
import org.example.utils.VPS_SYSTEM_PROMPT_CODE
import org.example.utils.VPS_SYSTEM_PROMPT_QA
import org.example.utils.VPS_SYSTEM_PROMPT_SOLVER

class ChangePromptCommand : Command {
    override fun matches(input: String): Boolean =
        input.equals("/changePrompt", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        println()
        println("Выберите новый system prompt:")
        println()
        println("=== Стандартные промпты ===")
        println("1 - Обычный ИИ помощник")
        println("2 - Логические задачи")
        println("3 - Токарь")
        println("4 - Пират 18 века")
        println()
        println("=== VPS-оптимизированные (для qwen2.5:0.5b) ===")
        println("5 - VPS: Краткий ассистент (рекомендуется)")
        println("6 - VPS: Программист")
        println("7 - VPS: Вопрос-ответ")
        println("8 - VPS: Логика")
        println()
        print("Ваш выбор (1-8): ")

        val choice = context.console.readLine("")?.trim()

        context.state.currentSystemPrompt = when (choice) {
            "1" -> SYSTEM_FORMAT_PROMPT
            "2" -> SYSTEM_FORMAT_PROMPT_LOGIC
            "3" -> SYSTEM_FORMAT_PROMPT_TOKAR
            "4" -> SYSTEM_FORMAT_PROMPT_PIRATE
            "5" -> VPS_SYSTEM_PROMPT_CONCISE
            "6" -> VPS_SYSTEM_PROMPT_CODE
            "7" -> VPS_SYSTEM_PROMPT_QA
            "8" -> VPS_SYSTEM_PROMPT_SOLVER
            else -> {
                println("Неизвестный выбор, оставляю прежний system prompt.")
                context.state.currentSystemPrompt
            }
        }

        val role = when (context.state.currentSystemPrompt) {
            SYSTEM_FORMAT_PROMPT -> "Обычный ИИ помощник"
            SYSTEM_FORMAT_PROMPT_LOGIC -> "Логические задачи"
            SYSTEM_FORMAT_PROMPT_TOKAR -> "Токарь"
            SYSTEM_FORMAT_PROMPT_PIRATE -> "Пират 18 века"
            VPS_SYSTEM_PROMPT_CONCISE -> "VPS: Краткий ассистент"
            VPS_SYSTEM_PROMPT_CODE -> "VPS: Программист"
            VPS_SYSTEM_PROMPT_QA -> "VPS: Вопрос-ответ"
            VPS_SYSTEM_PROMPT_SOLVER -> "VPS: Логика"
            else -> ""
        }

        if (role.isNotEmpty()) {
            println()
            println("System prompt обновлён: $role")
            if (role.startsWith("VPS:")) {
                println("Рекомендуемые настройки для VPS:")
                println("  /temperature 0.3  - для точных ответов")
                println("  /maxTokens 150    - для кратких ответов")
            }
            println()
        }
        return CommandResult.Continue
    }
}
