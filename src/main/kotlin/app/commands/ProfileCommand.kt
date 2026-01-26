package org.example.app.commands

import org.example.config.ProfileLoader

/**
 * Command to view and reload developer profile.
 * Usage: /profile - show current profile
 *        /profile reload - reload from file
 */
class ProfileCommand : Command {
    override fun matches(input: String): Boolean =
        input.startsWith("/profile", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val args = input.removePrefix("/profile").trim().lowercase()

        return when (args) {
            "reload" -> reloadProfile(context)
            else -> showProfile()
        }
    }

    private fun showProfile(): CommandResult {
        val profile = ProfileLoader.load()

        if (profile == null) {
            println("Профиль не найден. Создайте файл config/developer_profile.yaml")
            return CommandResult.Continue
        }

        println()
        println("═══ Профиль разработчика ═══")
        println("Имя: ${profile.name}")
        println("Роль: ${profile.role}")
        println("Компания: ${profile.company}")
        println("Проект: ${profile.project}")
        println("Уровень: ${profile.expertiseLevel}")
        println()
        println("Основной стек: ${profile.primaryStack.joinToString(", ")}")
        println()
        println("Стиль общения: ${profile.communication.style}")
        println("Обратная связь: ${profile.communication.feedback}")
        println("Объяснения: ${profile.communication.explanations}")
        println()
        if (profile.petProjects.isNotEmpty()) {
            println("Pet-проекты:")
            profile.petProjects.forEach {
                println("  • ${it.name}: ${it.description}")
            }
        }
        println("═════════════════════════════")
        println()

        return CommandResult.Continue
    }

    private fun reloadProfile(context: CommandContext): CommandResult {
        val profile = ProfileLoader.load()

        if (profile == null) {
            println("Не удалось загрузить профиль")
            return CommandResult.Continue
        }

        println("Профиль перезагружен: ${profile.name}")
        println("(Изменения применятся к новым сообщениям)")
        println()

        return CommandResult.Continue
    }
}
