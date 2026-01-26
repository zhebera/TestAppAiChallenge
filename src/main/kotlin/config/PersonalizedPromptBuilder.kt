package org.example.config

/**
 * Builds personalization text to append to system prompts.
 * Uses developer profile to customize agent behavior.
 */
object PersonalizedPromptBuilder {

    /**
     * Build personalization section for system prompt.
     */
    fun build(profile: DeveloperProfile): String = buildString {
        appendLine()
        appendLine("═══════════════════════════════════════════════════════════════")
        appendLine("ПЕРСОНАЛИЗАЦИЯ: ПРОФИЛЬ РАЗРАБОТЧИКА")
        appendLine("═══════════════════════════════════════════════════════════════")
        appendLine()

        // Basic info
        appendLine("Ты общаешься с: ${profile.name}")
        appendLine("Роль: ${profile.role} в ${profile.company}")
        appendLine("Текущий проект: ${profile.project}")
        appendLine("Уровень: ${profile.expertiseLevel}")
        appendLine()

        // Tech stack
        appendLine("ОСНОВНОЙ СТЕК (примеры кода давай на этих технологиях):")
        profile.primaryStack.forEach { appendLine("  • $it") }
        appendLine()

        if (profile.secondaryStack.isNotEmpty()) {
            appendLine("ДОПОЛНИТЕЛЬНЫЙ СТЕК (для pet-проектов):")
            profile.secondaryStack.forEach { appendLine("  • $it") }
            appendLine()
        }

        // Architecture preferences
        if (profile.architecture.patterns.isNotEmpty()) {
            appendLine("АРХИТЕКТУРНЫЕ ПРЕДПОЧТЕНИЯ:")
            appendLine("  Паттерны: ${profile.architecture.patterns.joinToString(", ")}")
            appendLine("  Принципы: ${profile.architecture.principles.joinToString(", ")}")
            if (profile.architecture.structure.isNotEmpty()) {
                appendLine("  Структура: ${profile.architecture.structure}")
            }
            appendLine()
        }

        // Communication style
        appendLine("СТИЛЬ ОБЩЕНИЯ:")
        when (profile.communication.style) {
            "concrete" -> appendLine("  • Отвечай конкретно и по делу, без воды")
            "detailed" -> appendLine("  • Давай детальные объяснения")
            else -> appendLine("  • Стандартный стиль общения")
        }
        when (profile.communication.feedback) {
            "honest" -> appendLine("  • Давай честную обратную связь, даже если негативную")
            "soft" -> appendLine("  • Мягкая обратная связь")
            else -> appendLine("  • Сбалансированная обратная связь")
        }
        when (profile.communication.explanations) {
            "minimal" -> appendLine("  • НЕ объясняй очевидные вещи для ${profile.expertiseLevel} уровня")
            "detailed" -> appendLine("  • Давай подробные объяснения")
            else -> appendLine("  • Объяснения по необходимости")
        }
        appendLine("  • Язык ответов: ${profile.communication.language}")
        appendLine()

        // Pet projects context
        if (profile.petProjects.isNotEmpty()) {
            appendLine("PET-ПРОЕКТЫ (можешь помочь с ними):")
            profile.petProjects.forEach { project ->
                appendLine("  • ${project.name}: ${project.description}")
                appendLine("    Стек: ${project.stack.joinToString(", ")}")
            }
            appendLine()
        }

        // Growth areas
        if (profile.growthAreas.isNotEmpty()) {
            appendLine("ОБЛАСТИ РОСТА (тут можно объяснять подробнее):")
            profile.growthAreas.forEach { appendLine("  • $it") }
            appendLine()
        }

        // Interests
        if (profile.interests.isNotEmpty()) {
            appendLine("ИНТЕРЕСЫ (можешь предлагать релевантное):")
            profile.interests.forEach { appendLine("  • $it") }
            appendLine()
        }

        appendLine("═══════════════════════════════════════════════════════════════")
    }

    /**
     * Build short personalization for prompts with limited context.
     */
    fun buildShort(profile: DeveloperProfile): String = buildString {
        appendLine()
        appendLine("---")
        appendLine("Пользователь: ${profile.name}, ${profile.role} (${profile.expertiseLevel})")
        appendLine("Стек: ${profile.primaryStack.take(4).joinToString(", ")}")
        appendLine("Стиль: конкретно, честно, без лишних объяснений")
        appendLine("---")
    }
}
