package org.example.team

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.network.LlmClient
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage
import org.example.mcp.server.tasks.TaskPriority
import org.example.mcp.server.tasks.TaskType

/**
 * Результат парсинга задачи из естественного языка.
 */
@Serializable
data class ParsedTaskData(
    val title: String,
    val description: String,
    val priority: String = "medium",
    val type: String = "feature",
    val labels: List<String> = emptyList(),
    val storyPoints: Int? = null,
    val suggestedAssignee: String? = null,
    val needsClarification: Boolean = false,
    val clarificationQuestions: List<String> = emptyList()
)

/**
 * Парсер задач из естественного языка.
 * Использует Haiku для быстрого извлечения параметров задачи из текста.
 */
class NaturalLanguageTaskParser(
    private val haikuClient: LlmClient?,
    private val json: Json
) {
    companion object {
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.3

        private val TASK_PARSING_PROMPT = """
Ты - парсер задач. Пользователь хочет создать задачу. Извлеки параметры из его сообщения.

Параметры задачи:
- title: краткий заголовок задачи (обязательно)
- description: подробное описание (если не указано - сгенерируй на основе title)
- priority: low, medium, high, critical (по умолчанию medium)
- type: feature, bug, tech_debt, spike, improvement (по умолчанию feature)
- labels: метки для категоризации (например: backend, frontend, api, ui, urgent)
- storyPoints: оценка сложности 1-13 (опционально)
- suggestedAssignee: имя исполнителя если упомянут (опционально)
- needsClarification: true если информации недостаточно
- clarificationQuestions: список уточняющих вопросов если нужно

Правила определения типа:
- bug/баг: ошибка, не работает, сломалось, fix
- feature/фича: новая функциональность, добавить, реализовать
- tech_debt: рефакторинг, переписать, оптимизировать
- spike: исследование, изучить, прототип
- improvement: улучшить, доработать

Правила определения приоритета:
- critical: блокер, срочно, ASAP, падает, не работает продакшн
- high: важно, скоро, высокий приоритет
- medium: обычная задача (по умолчанию)
- low: когда-нибудь, nice to have

Ответь ТОЛЬКО JSON:
{"title": "...", "description": "...", "priority": "medium", "type": "feature", "labels": [], "storyPoints": null, "suggestedAssignee": null, "needsClarification": false, "clarificationQuestions": []}
        """.trimIndent()
    }

    /**
     * Парсит запрос пользователя и извлекает параметры задачи.
     */
    suspend fun parse(userMessage: String): ParsedTaskData {
        if (haikuClient == null) {
            return parseByHeuristics(userMessage)
        }

        return try {
            val response = haikuClient.send(
                LlmRequest(
                    model = haikuClient.model,
                    messages = listOf(LlmMessage(ChatRole.USER, userMessage)),
                    systemPrompt = TASK_PARSING_PROMPT,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE
                )
            )

            parseTaskResponse(response.text, userMessage)
        } catch (e: Exception) {
            // Fallback на простой парсинг
            parseByHeuristics(userMessage)
        }
    }

    /**
     * Парсит ответ LLM в структуру ParsedTaskData.
     */
    private fun parseTaskResponse(responseText: String, originalMessage: String): ParsedTaskData {
        val jsonStart = responseText.indexOf('{')
        val jsonEnd = responseText.lastIndexOf('}')

        if (jsonStart == -1 || jsonEnd == -1) {
            return parseByHeuristics(originalMessage)
        }

        val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)

        return try {
            val parsed = json.decodeFromString<ParsedTaskData>(jsonStr)
            // Валидируем и нормализуем
            normalizeTaskData(parsed)
        } catch (e: Exception) {
            parseByHeuristics(originalMessage)
        }
    }

    /**
     * Нормализует и валидирует данные задачи.
     */
    private fun normalizeTaskData(data: ParsedTaskData): ParsedTaskData {
        val normalizedPriority = try {
            TaskPriority.valueOf(data.priority.uppercase()).name.lowercase()
        } catch (e: Exception) {
            "medium"
        }

        val normalizedType = try {
            TaskType.valueOf(data.type.uppercase()).name.lowercase()
        } catch (e: Exception) {
            "feature"
        }

        val normalizedStoryPoints = data.storyPoints?.let {
            when {
                it < 1 -> 1
                it > 13 -> 13
                else -> it
            }
        }

        return data.copy(
            priority = normalizedPriority,
            type = normalizedType,
            storyPoints = normalizedStoryPoints
        )
    }

    /**
     * Fallback парсинг на основе эвристик.
     */
    fun parseByHeuristics(message: String): ParsedTaskData {
        val lowerMessage = message.lowercase()

        // Извлекаем тип
        val type = when {
            listOf("баг", "bug", "ошибк", "fix", "исправ", "не работает", "сломал").any { lowerMessage.contains(it) } -> "bug"
            listOf("рефактор", "переписа", "оптимиз", "tech debt", "долг").any { lowerMessage.contains(it) } -> "tech_debt"
            listOf("исследов", "spike", "прототип", "изучи").any { lowerMessage.contains(it) } -> "spike"
            listOf("улучш", "доработ", "improvement").any { lowerMessage.contains(it) } -> "improvement"
            else -> "feature"
        }

        // Извлекаем приоритет
        val priority = when {
            listOf("critical", "критическ", "срочно", "asap", "блокер", "падает").any { lowerMessage.contains(it) } -> "critical"
            listOf("high", "важно", "высок", "приоритет").any { lowerMessage.contains(it) } -> "high"
            listOf("low", "низк", "когда-нибудь", "nice to have").any { lowerMessage.contains(it) } -> "low"
            else -> "medium"
        }

        // Извлекаем метки
        val labels = mutableListOf<String>()
        if (lowerMessage.contains("backend") || lowerMessage.contains("api") || lowerMessage.contains("сервер")) {
            labels.add("backend")
        }
        if (lowerMessage.contains("frontend") || lowerMessage.contains("ui") || lowerMessage.contains("интерфейс")) {
            labels.add("frontend")
        }
        if (lowerMessage.contains("database") || lowerMessage.contains("бд") || lowerMessage.contains("база")) {
            labels.add("database")
        }
        if (type == "bug") {
            labels.add("bug")
        }

        // Генерируем title - берём основную часть после ключевых слов
        val cleanedMessage = message
            .replace(Regex("создай|добавь|сделай|нужно|надо|требуется", RegexOption.IGNORE_CASE), "")
            .replace(Regex("задачу?|таск", RegexOption.IGNORE_CASE), "")
            .replace(Regex("с приоритетом \\w+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("типа \\w+", RegexOption.IGNORE_CASE), "")
            .trim()

        val title = if (cleanedMessage.length > 100) {
            cleanedMessage.take(100) + "..."
        } else if (cleanedMessage.isBlank()) {
            "Новая задача"
        } else {
            cleanedMessage.replaceFirstChar { it.uppercaseChar() }
        }

        return ParsedTaskData(
            title = title,
            description = message,
            priority = priority,
            type = type,
            labels = labels,
            needsClarification = cleanedMessage.length < 10  // Слишком короткое описание
        )
    }

    /**
     * Форматирует результат парсинга для подтверждения пользователем.
     */
    fun formatForConfirmation(data: ParsedTaskData): String {
        return buildString {
            appendLine("=== Создание задачи ===")
            appendLine()
            appendLine("Заголовок: ${data.title}")
            appendLine("Описание: ${data.description}")
            appendLine("Тип: ${formatType(data.type)}")
            appendLine("Приоритет: ${data.priority.uppercase()}")
            if (data.labels.isNotEmpty()) {
                appendLine("Метки: ${data.labels.joinToString(", ")}")
            }
            if (data.storyPoints != null) {
                appendLine("Story Points: ${data.storyPoints}")
            }
            if (data.suggestedAssignee != null) {
                appendLine("Предложенный исполнитель: ${data.suggestedAssignee}")
            }

            if (data.needsClarification && data.clarificationQuestions.isNotEmpty()) {
                appendLine()
                appendLine("Уточняющие вопросы:")
                data.clarificationQuestions.forEach { q ->
                    appendLine("  - $q")
                }
            }
        }
    }

    private fun formatType(type: String): String = when (type.lowercase()) {
        "feature" -> "Фича"
        "bug" -> "Баг"
        "tech_debt" -> "Тех. долг"
        "spike" -> "Исследование"
        "improvement" -> "Улучшение"
        else -> type
    }
}
