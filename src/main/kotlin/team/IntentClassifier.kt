package org.example.team

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.app.AppConfig
import org.example.data.dto.LlmRequest
import org.example.data.network.LlmClient
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage

/**
 * Классы интентов пользователя.
 */
enum class UserIntent {
    TASK_QUERY,         // Запрос информации о задачах
    TASK_CREATE,        // Создание новой задачи
    TASK_UPDATE,        // Изменение задачи (статус, приоритет, исполнитель)
    PROJECT_STATUS,     // Запрос статуса проекта/спринта
    RECOMMENDATIONS,    // Запрос рекомендаций по приоритетам
    CODE_QUESTION,      // Вопрос о коде проекта (требует RAG)
    TEAM_INFO,          // Информация о команде, загрузке
    GENERAL             // Общий вопрос, не требующий специального контекста
}

/**
 * Результат классификации интента.
 */
@Serializable
data class IntentClassification(
    val intent: String,
    val confidence: Double,
    val entities: IntentEntities = IntentEntities()
)

/**
 * Извлечённые сущности из запроса.
 */
@Serializable
data class IntentEntities(
    val taskId: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    val taskType: String? = null,
    val keywords: List<String> = emptyList()
)

/**
 * Сервис классификации интентов с использованием Haiku.
 * Быстрый и дешёвый пре-запрос для определения намерения пользователя.
 */
class IntentClassifier(
    private val haikuClient: LlmClient?,
    private val json: Json
) {
    companion object {
        private const val MAX_TOKENS = 256
        private const val TEMPERATURE = 0.1  // Низкая температура для детерминированности

        private val INTENT_CLASSIFICATION_PROMPT = """
Ты - классификатор интентов. Проанализируй сообщение пользователя и определи его намерение.

Возможные интенты:
- TASK_QUERY: запрос информации о задачах (поиск, фильтр, просмотр)
- TASK_CREATE: создание новой задачи (слова: создай, добавь, новая задача)
- TASK_UPDATE: изменение задачи (смена статуса, приоритета, назначение)
- PROJECT_STATUS: запрос статуса проекта или спринта
- RECOMMENDATIONS: запрос рекомендаций, советов по приоритетам
- CODE_QUESTION: вопрос о коде, архитектуре, технической реализации
- TEAM_INFO: информация о команде, кто чем занимается, загрузка
- GENERAL: общий вопрос, приветствие, не связанный с задачами

Также извлеки сущности если они есть:
- taskId: ID задачи (task_001, task_002, etc.)
- priority: приоритет (low, medium, high, critical)
- status: статус (backlog, todo, in_progress, review, testing, done)
- assignee: имя исполнителя
- taskType: тип задачи (feature, bug, tech_debt, spike, improvement)
- keywords: ключевые слова для поиска

Ответь ТОЛЬКО JSON в формате:
{"intent": "INTENT_NAME", "confidence": 0.95, "entities": {"taskId": null, "priority": null, "status": null, "assignee": null, "taskType": null, "keywords": []}}
        """.trimIndent()
    }

    /**
     * Классифицирует интент пользователя.
     * Использует Haiku для быстрой и дешёвой классификации.
     */
    suspend fun classify(message: String): IntentClassification {
        if (haikuClient == null) {
            return classifyByHeuristics(message)
        }

        return try {
            val response = haikuClient.send(
                LlmRequest(
                    model = haikuClient.model,
                    messages = listOf(LlmMessage(ChatRole.USER, message)),
                    systemPrompt = INTENT_CLASSIFICATION_PROMPT,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE
                )
            )

            parseIntentResponse(response.text)
        } catch (e: Exception) {
            // Fallback на эвристики если LLM недоступен
            classifyByHeuristics(message)
        }
    }

    /**
     * Парсит ответ LLM в структуру IntentClassification.
     */
    private fun parseIntentResponse(responseText: String): IntentClassification {
        // Извлекаем JSON из ответа
        val jsonStart = responseText.indexOf('{')
        val jsonEnd = responseText.lastIndexOf('}')

        if (jsonStart == -1 || jsonEnd == -1) {
            return classifyByHeuristics(responseText)
        }

        val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)

        return try {
            json.decodeFromString<IntentClassification>(jsonStr)
        } catch (e: Exception) {
            classifyByHeuristics(responseText)
        }
    }

    /**
     * Fallback классификация на основе эвристик (ключевых слов).
     */
    fun classifyByHeuristics(message: String): IntentClassification {
        val lowerMessage = message.lowercase()

        // Определяем интент по ключевым словам
        val intent = when {
            // Task creation
            listOf("создай", "добавь", "новая задача", "create", "add task").any { lowerMessage.contains(it) } ->
                UserIntent.TASK_CREATE

            // Task update
            listOf("обнови", "измени", "поменяй", "назначь", "переведи в", "update", "assign", "change status").any { lowerMessage.contains(it) } ->
                UserIntent.TASK_UPDATE

            // Project status
            listOf("статус проекта", "статус спринта", "прогресс", "сколько задач", "project status", "sprint").any { lowerMessage.contains(it) } ->
                UserIntent.PROJECT_STATUS

            // Recommendations
            listOf("рекоменд", "что делать", "приоритет", "первым", "recommend", "priority", "what to do").any { lowerMessage.contains(it) } ->
                UserIntent.RECOMMENDATIONS

            // Team info
            listOf("команд", "загрузка", "кто", "разработчик", "team", "workload", "developer").any { lowerMessage.contains(it) } ->
                UserIntent.TEAM_INFO

            // Task query
            listOf("покажи", "найди", "задач", "список", "task_", "show", "find", "list", "tasks").any { lowerMessage.contains(it) } ->
                UserIntent.TASK_QUERY

            // Code question
            listOf("код", "архитектур", "как работает", "реализ", "класс", "функци", "code", "architecture", "how", "implement").any { lowerMessage.contains(it) } ->
                UserIntent.CODE_QUESTION

            else -> UserIntent.GENERAL
        }

        // Извлекаем сущности
        val entities = extractEntitiesByHeuristics(message)

        return IntentClassification(
            intent = intent.name,
            confidence = 0.7,  // Эвристика менее уверенная
            entities = entities
        )
    }

    /**
     * Извлекает сущности из сообщения по эвристикам.
     */
    fun extractEntitiesByHeuristics(message: String): IntentEntities {
        val lowerMessage = message.lowercase()

        // Ищем task_id
        val taskIdRegex = Regex("""task_\d+""")
        val taskId = taskIdRegex.find(lowerMessage)?.value

        // Ищем приоритет
        val priority = when {
            lowerMessage.contains("critical") || lowerMessage.contains("критическ") -> "critical"
            lowerMessage.contains("high") || lowerMessage.contains("высок") -> "high"
            lowerMessage.contains("medium") || lowerMessage.contains("средн") -> "medium"
            lowerMessage.contains("low") || lowerMessage.contains("низк") -> "low"
            else -> null
        }

        // Ищем статус
        val status = when {
            lowerMessage.contains("backlog") || lowerMessage.contains("бэклог") -> "backlog"
            lowerMessage.contains("todo") || lowerMessage.contains("к выполнению") -> "todo"
            lowerMessage.contains("in_progress") || lowerMessage.contains("в работе") -> "in_progress"
            lowerMessage.contains("review") || lowerMessage.contains("ревью") -> "review"
            lowerMessage.contains("testing") || lowerMessage.contains("тестиров") -> "testing"
            lowerMessage.contains("done") || lowerMessage.contains("выполнен") -> "done"
            else -> null
        }

        // Ищем тип задачи
        val taskType = when {
            lowerMessage.contains("bug") || lowerMessage.contains("баг") -> "bug"
            lowerMessage.contains("feature") || lowerMessage.contains("фич") -> "feature"
            lowerMessage.contains("tech_debt") || lowerMessage.contains("долг") -> "tech_debt"
            lowerMessage.contains("spike") || lowerMessage.contains("исследован") -> "spike"
            lowerMessage.contains("improvement") || lowerMessage.contains("улучшен") -> "improvement"
            else -> null
        }

        return IntentEntities(
            taskId = taskId,
            priority = priority,
            status = status,
            taskType = taskType
        )
    }

    /**
     * Конвертирует строку интента в enum.
     */
    fun toUserIntent(intentStr: String): UserIntent {
        return try {
            UserIntent.valueOf(intentStr.uppercase())
        } catch (e: Exception) {
            UserIntent.GENERAL
        }
    }
}
