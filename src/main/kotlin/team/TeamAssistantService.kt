package org.example.team

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.example.data.dto.LlmRequest
import org.example.data.mcp.McpClient
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.example.data.rag.RagService
import org.example.data.rag.ReadinessResult
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage
import org.example.mcp.server.tasks.TaskDataManager
import org.example.mcp.server.tasks.TaskPriority
import org.example.mcp.server.tasks.TaskStatus
import org.example.mcp.server.tasks.TaskType

/**
 * Сервис командного ассистента.
 * Оркестрирует RAG (знание проекта), TaskDataManager (управление задачами) и GitHub MCP.
 * Использует intent classification для оптимальной загрузки контекста.
 */
class TeamAssistantService(
    private val ragService: RagService,
    private val llmClient: LlmClient,
    private val taskDataManager: TaskDataManager,
    private val githubMcpClient: McpClient?,
    private val json: Json,
    private val intentClassifier: IntentClassifier? = null,
    private val taskParser: NaturalLanguageTaskParser? = null
) {
    companion object {
        private const val RAG_TOP_K = 5
        private const val RAG_INITIAL_TOP_K = 10
        private const val RAG_MIN_SIMILARITY = 0.35f
        private const val LLM_MAX_TOKENS = 2048
    }

    /**
     * Обработка сообщения от пользователя со стримингом.
     * Использует intent classification для оптимальной загрузки контекста.
     */
    suspend fun processMessageStream(
        message: String,
        includeProjectContext: Boolean = true,
        includeTasksContext: Boolean = true
    ): Flow<StreamEvent> {
        // 1. Классифицируем интент для оптимальной загрузки контекста
        val intentResult = intentClassifier?.classify(message)
        val intent = intentResult?.let { intentClassifier.toUserIntent(it.intent) } ?: UserIntent.GENERAL

        // 2. Получить контекст на основе интента
        val projectContext = if (shouldLoadProjectContext(intent, includeProjectContext)) {
            try {
                val ragResult = ragService.searchWithReranking(
                    query = message,
                    topK = RAG_TOP_K,
                    initialTopK = RAG_INITIAL_TOP_K,
                    minSimilarity = RAG_MIN_SIMILARITY
                )
                ragResult.formattedContext
            } catch (e: Exception) {
                ""
            }
        } else ""

        // 3. Получить контекст задач на основе интента
        val tasksContext = if (shouldLoadTasksContext(intent, includeTasksContext)) {
            try {
                getTasksContextByIntent(message, intent, intentResult?.entities)
            } catch (e: Exception) {
                ""
            }
        } else ""

        // 4. Получить информацию о репозитории (если релевантно)
        val githubContext = if (githubMcpClient != null && isGitHubRelevant(message)) {
            try {
                getGitHubContext()
            } catch (e: Exception) {
                ""
            }
        } else ""

        // 5. Сформировать промпт с guardrails
        val systemPrompt = TeamPrompts.buildPromptWithGuardrails(
            projectContext = projectContext,
            tasksContext = tasksContext,
            githubContext = githubContext,
            intent = intent
        )

        // 6. Отправить в LLM
        return llmClient.sendStream(
            LlmRequest(
                model = llmClient.model,
                messages = listOf(LlmMessage(ChatRole.USER, message)),
                systemPrompt = systemPrompt,
                maxTokens = LLM_MAX_TOKENS,
                temperature = 0.7
            )
        )
    }

    /**
     * Создание задачи из естественного языка.
     * Парсит сообщение и извлекает параметры задачи.
     */
    suspend fun createTaskFromNaturalLanguage(message: String): NLTaskCreationResult {
        if (taskParser == null) {
            return NLTaskCreationResult.Error("NL parser не инициализирован")
        }

        val parsedData = taskParser.parse(message)

        if (parsedData.needsClarification && parsedData.clarificationQuestions.isNotEmpty()) {
            return NLTaskCreationResult.NeedsClarification(
                parsedData = parsedData,
                questions = parsedData.clarificationQuestions
            )
        }

        // Создаём задачу
        val result = createTask(
            title = parsedData.title,
            description = parsedData.description,
            priority = parsedData.priority,
            type = parsedData.type
        )

        return NLTaskCreationResult.Success(
            parsedData = parsedData,
            creationResult = result
        )
    }

    /**
     * Получить preview задачи перед созданием.
     */
    suspend fun previewTaskFromNaturalLanguage(message: String): ParsedTaskData? {
        return taskParser?.parse(message)
    }

    /**
     * Классифицировать интент сообщения.
     */
    suspend fun classifyIntent(message: String): IntentClassification? {
        return intentClassifier?.classify(message)
    }

    /**
     * Выполнить действие с задачей.
     * Использует TaskDataManager напрямую без MCP.
     */
    fun executeTaskAction(action: TaskAction): TaskActionResult {
        return try {
            when (action) {
                is TaskAction.GetStatus -> {
                    val status = taskDataManager.getProjectStatus()
                    TaskActionResult.Success(taskDataManager.formatProjectStatusForLlm(status))
                }

                is TaskAction.ListTasks -> {
                    val priority = action.priority?.let {
                        try { TaskPriority.valueOf(it.uppercase()) } catch (_: Exception) { null }
                    }
                    val status = action.status?.let {
                        try { TaskStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
                    }

                    val tasks = taskDataManager.searchTasks(
                        status = status,
                        priority = priority,
                        assigneeId = action.assigneeId
                    )
                    TaskActionResult.Success(taskDataManager.formatTasksListForLlm(tasks))
                }

                is TaskAction.GetTask -> {
                    val task = taskDataManager.getTaskById(action.taskId)
                    if (task != null) {
                        TaskActionResult.Success(taskDataManager.formatTaskDetailForLlm(task))
                    } else {
                        TaskActionResult.Error("Задача '${action.taskId}' не найдена")
                    }
                }

                is TaskAction.CreateTask -> {
                    val priority = try {
                        TaskPriority.valueOf(action.priority.uppercase())
                    } catch (_: Exception) {
                        TaskPriority.MEDIUM
                    }
                    val type = try {
                        TaskType.valueOf(action.type.uppercase())
                    } catch (_: Exception) {
                        TaskType.FEATURE
                    }

                    val task = taskDataManager.createTask(
                        title = action.title,
                        description = action.description,
                        priority = priority,
                        type = type,
                        assigneeId = action.assigneeId,
                        sprintId = action.sprintId,
                        reporterId = "team_assistant"
                    )
                    TaskActionResult.Success("Задача создана: ${task.id}\n${taskDataManager.formatTaskDetailForLlm(task)}")
                }

                is TaskAction.UpdateStatus -> {
                    val newStatus = try {
                        TaskStatus.valueOf(action.status.uppercase())
                    } catch (_: Exception) {
                        return TaskActionResult.Error("Неверный статус: ${action.status}")
                    }

                    val updated = taskDataManager.updateTaskStatus(action.taskId, newStatus)
                    if (updated != null) {
                        TaskActionResult.Success("Статус обновлён: ${updated.status}")
                    } else {
                        TaskActionResult.Error("Задача не найдена")
                    }
                }

                is TaskAction.UpdatePriority -> {
                    val newPriority = try {
                        TaskPriority.valueOf(action.priority.uppercase())
                    } catch (_: Exception) {
                        return TaskActionResult.Error("Неверный приоритет: ${action.priority}")
                    }

                    val updated = taskDataManager.updateTaskPriority(action.taskId, newPriority)
                    if (updated != null) {
                        TaskActionResult.Success("Приоритет обновлён: ${updated.priority}")
                    } else {
                        TaskActionResult.Error("Задача не найдена")
                    }
                }

                is TaskAction.AssignTask -> {
                    val updated = taskDataManager.assignTask(action.taskId, action.assigneeId)
                    if (updated != null) {
                        val assigneeText = action.assigneeId ?: "снят"
                        TaskActionResult.Success("Исполнитель: $assigneeText")
                    } else {
                        TaskActionResult.Error("Задача не найдена")
                    }
                }

                is TaskAction.DeleteTask -> {
                    val deleted = taskDataManager.deleteTask(action.taskId)
                    if (deleted) {
                        TaskActionResult.Success("Задача '${action.taskId}' удалена")
                    } else {
                        TaskActionResult.Error("Задача не найдена")
                    }
                }

                is TaskAction.GetRecommendations -> {
                    val recommendations = taskDataManager.getRecommendations()
                    TaskActionResult.Success(recommendations)
                }

                is TaskAction.GetTeamWorkload -> {
                    val workload = taskDataManager.formatTeamWorkloadForLlm()
                    TaskActionResult.Success(workload)
                }
            }
        } catch (e: Exception) {
            TaskActionResult.Error("Ошибка выполнения: ${e.message}")
        }
    }

    /**
     * Получить статус проекта напрямую.
     */
    suspend fun getProjectStatus(): String {
        return when (val result = executeTaskAction(TaskAction.GetStatus)) {
            is TaskActionResult.Success -> result.data
            is TaskActionResult.Error -> "Ошибка: ${result.message}"
        }
    }

    /**
     * Получить задачи по приоритету.
     */
    suspend fun getTasksByPriority(priority: String): String {
        return when (val result = executeTaskAction(TaskAction.ListTasks(priority = priority))) {
            is TaskActionResult.Success -> result.data
            is TaskActionResult.Error -> "Ошибка: ${result.message}"
        }
    }

    /**
     * Получить рекомендации по приоритетам.
     */
    suspend fun getRecommendations(context: String? = null): String {
        return when (val result = executeTaskAction(TaskAction.GetRecommendations(context))) {
            is TaskActionResult.Success -> result.data
            is TaskActionResult.Error -> "Ошибка: ${result.message}"
        }
    }

    /**
     * Создать задачу.
     */
    suspend fun createTask(
        title: String,
        description: String,
        priority: String = "medium",
        type: String = "feature",
        assigneeId: String? = null,
        sprintId: String? = null
    ): String {
        val action = TaskAction.CreateTask(
            title = title,
            description = description,
            priority = priority,
            type = type,
            assigneeId = assigneeId,
            sprintId = sprintId
        )
        return when (val result = executeTaskAction(action)) {
            is TaskActionResult.Success -> result.data
            is TaskActionResult.Error -> "Ошибка создания задачи: ${result.message}"
        }
    }

    /**
     * Проверить статус RAG.
     */
    suspend fun checkRagStatus(): RagStatusInfo {
        return try {
            val readiness = ragService.checkReadiness()
            val stats = ragService.getIndexStats()

            when (readiness) {
                is ReadinessResult.Ready -> RagStatusInfo(
                    available = true,
                    indexedFiles = stats.indexedFiles.size,
                    totalChunks = stats.totalChunks.toInt()
                )
                is ReadinessResult.OllamaNotRunning -> RagStatusInfo(
                    available = false,
                    error = "Ollama не запущена"
                )
                is ReadinessResult.ModelNotFound -> RagStatusInfo(
                    available = false,
                    error = "Модель ${readiness.model} не найдена"
                )
            }
        } catch (e: Exception) {
            RagStatusInfo(available = false, error = e.message)
        }
    }

    /**
     * Проверить статус подключений.
     */
    fun checkMcpStatus(): McpStatusInfo {
        return McpStatusInfo(
            tasksConnected = true,  // TaskDataManager всегда доступен
            githubConnected = githubMcpClient?.isConnected == true
        )
    }

    // ==================== Private helpers ====================

    private fun shouldLoadProjectContext(intent: UserIntent, enabled: Boolean): Boolean {
        if (!enabled) return false
        return intent in listOf(
            UserIntent.CODE_QUESTION,
            UserIntent.GENERAL
        )
    }

    private fun shouldLoadTasksContext(intent: UserIntent, enabled: Boolean): Boolean {
        if (!enabled) return false
        return intent in listOf(
            UserIntent.TASK_QUERY,
            UserIntent.TASK_CREATE,
            UserIntent.TASK_UPDATE,
            UserIntent.PROJECT_STATUS,
            UserIntent.RECOMMENDATIONS,
            UserIntent.TEAM_INFO
        )
    }

    private fun getTasksContextByIntent(
        query: String,
        intent: UserIntent,
        entities: IntentEntities?
    ): String {
        val sb = StringBuilder()

        when (intent) {
            UserIntent.PROJECT_STATUS -> {
                val status = taskDataManager.getProjectStatus()
                sb.appendLine(taskDataManager.formatProjectStatusForLlm(status))
            }

            UserIntent.TASK_QUERY -> {
                // Если есть конкретный task_id - загружаем его
                entities?.taskId?.let { taskId ->
                    val task = taskDataManager.getTaskById(taskId)
                    if (task != null) {
                        sb.appendLine(taskDataManager.formatTaskDetailForLlm(task))
                        return sb.toString()
                    }
                }

                // Иначе загружаем по фильтрам
                val priority = entities?.priority?.let {
                    try { TaskPriority.valueOf(it.uppercase()) } catch (_: Exception) { null }
                }
                val status = entities?.status?.let {
                    try { TaskStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
                }

                val tasks = taskDataManager.searchTasks(status = status, priority = priority)
                sb.appendLine(taskDataManager.formatTasksListForLlm(tasks))
            }

            UserIntent.RECOMMENDATIONS -> {
                val status = taskDataManager.getProjectStatus()
                sb.appendLine(taskDataManager.formatProjectStatusForLlm(status))
                sb.appendLine()
                sb.appendLine("### Рекомендации:")
                sb.appendLine(taskDataManager.getRecommendations())
            }

            UserIntent.TEAM_INFO -> {
                sb.appendLine(taskDataManager.formatTeamWorkloadForLlm())
            }

            UserIntent.TASK_CREATE, UserIntent.TASK_UPDATE -> {
                val status = taskDataManager.getProjectStatus()
                sb.appendLine(taskDataManager.formatProjectStatusForLlm(status))

                // Для update - загружаем конкретную задачу
                if (intent == UserIntent.TASK_UPDATE && entities?.taskId != null) {
                    val task = taskDataManager.getTaskById(entities.taskId)
                    if (task != null) {
                        sb.appendLine()
                        sb.appendLine("### Текущая задача:")
                        sb.appendLine(taskDataManager.formatTaskDetailForLlm(task))
                    }
                }
            }

            else -> {
                getBasicTasksContext(query, sb)
            }
        }

        return sb.toString()
    }

    private fun getBasicTasksContext(query: String, sb: StringBuilder) {
        // Получить статус проекта
        val status = taskDataManager.getProjectStatus()
        sb.appendLine(taskDataManager.formatProjectStatusForLlm(status))
        sb.appendLine()

        // Если запрос про high/critical задачи - подгрузить их
        if (query.contains("high", ignoreCase = true) ||
            query.contains("critical", ignoreCase = true) ||
            query.contains("приоритет", ignoreCase = true) ||
            query.contains("срочн", ignoreCase = true)
        ) {
            val criticalTasks = taskDataManager.searchTasks(priority = TaskPriority.CRITICAL)
            if (criticalTasks.isNotEmpty()) {
                sb.appendLine("### CRITICAL задачи:")
                sb.appendLine(taskDataManager.formatTasksListForLlm(criticalTasks))
            }

            val highTasks = taskDataManager.searchTasks(priority = TaskPriority.HIGH)
            if (highTasks.isNotEmpty()) {
                sb.appendLine("### HIGH задачи:")
                sb.appendLine(taskDataManager.formatTasksListForLlm(highTasks))
            }
        }
    }

    private suspend fun getGitHubContext(): String {
        if (githubMcpClient == null) return ""

        return try {
            val result = githubMcpClient.callTool("get_repo_info")
            result.content.firstOrNull()?.text ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun isGitHubRelevant(message: String): Boolean {
        val keywords = listOf("git", "branch", "pr", "pull request", "commit", "push", "репозитор")
        return keywords.any { message.contains(it, ignoreCase = true) }
    }
}

// ==================== Data classes ====================

sealed class TaskAction {
    data object GetStatus : TaskAction()
    data class ListTasks(
        val status: String? = null,
        val priority: String? = null,
        val assigneeId: String? = null
    ) : TaskAction()
    data class GetTask(val taskId: String) : TaskAction()
    data class CreateTask(
        val title: String,
        val description: String,
        val priority: String,
        val type: String,
        val assigneeId: String? = null,
        val sprintId: String? = null
    ) : TaskAction()
    data class UpdateStatus(val taskId: String, val status: String) : TaskAction()
    data class UpdatePriority(val taskId: String, val priority: String) : TaskAction()
    data class AssignTask(val taskId: String, val assigneeId: String?) : TaskAction()
    data class DeleteTask(val taskId: String) : TaskAction()
    data class GetRecommendations(val context: String? = null) : TaskAction()
    data object GetTeamWorkload : TaskAction()
}

sealed class TaskActionResult {
    data class Success(val data: String) : TaskActionResult()
    data class Error(val message: String) : TaskActionResult()
}

sealed class NLTaskCreationResult {
    data class Success(val parsedData: ParsedTaskData, val creationResult: String) : NLTaskCreationResult()
    data class NeedsClarification(val parsedData: ParsedTaskData, val questions: List<String>) : NLTaskCreationResult()
    data class Error(val message: String) : NLTaskCreationResult()
}

data class RagStatusInfo(
    val available: Boolean,
    val indexedFiles: Int = 0,
    val totalChunks: Int = 0,
    val error: String? = null
)

data class McpStatusInfo(
    val tasksConnected: Boolean,
    val githubConnected: Boolean
)
