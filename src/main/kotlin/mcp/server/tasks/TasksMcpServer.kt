package org.example.mcp.server.tasks

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * MCP сервер для управления задачами проекта.
 *
 * Инструменты:
 * - get_project_status: общий статус проекта и спринта
 * - get_task: получить задачу по ID
 * - list_tasks: список задач с фильтрацией
 * - create_task: создать новую задачу
 * - update_task_status: изменить статус задачи
 * - update_task_priority: изменить приоритет задачи
 * - assign_task: назначить исполнителя
 * - add_comment: добавить комментарий к задаче
 * - get_recommendations: получить рекомендации по приоритетам
 * - get_team_workload: загрузка участников команды
 */
class TasksMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val dataManager = TaskDataManager()

    fun run() {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                if (response.isNotEmpty()) {
                    println(json.encodeToString(response))
                    System.out.flush()
                }
            } catch (e: Exception) {
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    }
                }
                println(json.encodeToString(errorResponse))
                System.out.flush()
            }
        }
    }

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> JsonObject(emptyMap())
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "tasks-mcp")
                    put("version", "1.0.0")
                }
            }
        }
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    // get_project_status
                    addJsonObject {
                        put("name", "get_project_status")
                        put("description", "Получить общий статус проекта: количество задач, прогресс спринта, критические задачи")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // get_task
                    addJsonObject {
                        put("name", "get_task")
                        put("description", "Получить детальную информацию о задаче по ID")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи")
                                }
                            }
                            putJsonArray("required") { add("task_id") }
                        }
                    }

                    // list_tasks
                    addJsonObject {
                        put("name", "list_tasks")
                        put("description", "Список задач с фильтрацией по статусу, приоритету, исполнителю или спринту")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "Поиск по названию и описанию")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "Фильтр по статусу")
                                    putJsonArray("enum") {
                                        add("backlog"); add("todo"); add("in_progress")
                                        add("review"); add("testing"); add("done")
                                    }
                                }
                                putJsonObject("priority") {
                                    put("type", "string")
                                    put("description", "Фильтр по приоритету")
                                    putJsonArray("enum") {
                                        add("low"); add("medium"); add("high"); add("critical")
                                    }
                                }
                                putJsonObject("type") {
                                    put("type", "string")
                                    put("description", "Фильтр по типу задачи")
                                    putJsonArray("enum") {
                                        add("feature"); add("bug"); add("tech_debt"); add("spike"); add("improvement")
                                    }
                                }
                                putJsonObject("assignee_id") {
                                    put("type", "string")
                                    put("description", "ID исполнителя")
                                }
                                putJsonObject("sprint_id") {
                                    put("type", "string")
                                    put("description", "ID спринта")
                                }
                            }
                        }
                    }

                    // create_task
                    addJsonObject {
                        put("name", "create_task")
                        put("description", "Создать новую задачу в проекте")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("title") {
                                    put("type", "string")
                                    put("description", "Заголовок задачи")
                                }
                                putJsonObject("description") {
                                    put("type", "string")
                                    put("description", "Описание задачи")
                                }
                                putJsonObject("priority") {
                                    put("type", "string")
                                    put("description", "Приоритет: low, medium, high, critical")
                                    put("default", "medium")
                                }
                                putJsonObject("type") {
                                    put("type", "string")
                                    put("description", "Тип: feature, bug, tech_debt, spike, improvement")
                                    put("default", "feature")
                                }
                                putJsonObject("reporter_id") {
                                    put("type", "string")
                                    put("description", "ID создателя задачи")
                                    put("default", "pm_1")
                                }
                                putJsonObject("assignee_id") {
                                    put("type", "string")
                                    put("description", "ID исполнителя (опционально)")
                                }
                                putJsonObject("sprint_id") {
                                    put("type", "string")
                                    put("description", "ID спринта (опционально)")
                                }
                                putJsonObject("labels") {
                                    put("type", "array")
                                    put("description", "Метки задачи")
                                    putJsonObject("items") { put("type", "string") }
                                }
                                putJsonObject("story_points") {
                                    put("type", "integer")
                                    put("description", "Story points (оценка сложности)")
                                }
                            }
                            putJsonArray("required") { add("title"); add("description") }
                        }
                    }

                    // update_task_status
                    addJsonObject {
                        put("name", "update_task_status")
                        put("description", "Обновить статус задачи")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "Новый статус")
                                    putJsonArray("enum") {
                                        add("backlog"); add("todo"); add("in_progress")
                                        add("review"); add("testing"); add("done")
                                    }
                                }
                            }
                            putJsonArray("required") { add("task_id"); add("status") }
                        }
                    }

                    // update_task_priority
                    addJsonObject {
                        put("name", "update_task_priority")
                        put("description", "Изменить приоритет задачи")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи")
                                }
                                putJsonObject("priority") {
                                    put("type", "string")
                                    put("description", "Новый приоритет")
                                    putJsonArray("enum") {
                                        add("low"); add("medium"); add("high"); add("critical")
                                    }
                                }
                            }
                            putJsonArray("required") { add("task_id"); add("priority") }
                        }
                    }

                    // assign_task
                    addJsonObject {
                        put("name", "assign_task")
                        put("description", "Назначить исполнителя на задачу")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи")
                                }
                                putJsonObject("assignee_id") {
                                    put("type", "string")
                                    put("description", "ID исполнителя (пусто для снятия назначения)")
                                }
                            }
                            putJsonArray("required") { add("task_id") }
                        }
                    }

                    // add_comment
                    addJsonObject {
                        put("name", "add_comment")
                        put("description", "Добавить комментарий к задаче")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи")
                                }
                                putJsonObject("author_id") {
                                    put("type", "string")
                                    put("description", "ID автора комментария")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Текст комментария")
                                }
                            }
                            putJsonArray("required") { add("task_id"); add("content") }
                        }
                    }

                    // get_recommendations
                    addJsonObject {
                        put("name", "get_recommendations")
                        put("description", "Получить AI рекомендации по приоритетам: какие задачи делать первыми и почему")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("context") {
                                    put("type", "string")
                                    put("description", "Дополнительный контекст для рекомендаций")
                                }
                            }
                        }
                    }

                    // get_team_workload
                    addJsonObject {
                        put("name", "get_team_workload")
                        put("description", "Посмотреть текущую загрузку участников команды")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // delete_task
                    addJsonObject {
                        put("name", "delete_task")
                        put("description", "Удалить задачу (требует подтверждения)")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("task_id") {
                                    put("type", "string")
                                    put("description", "ID задачи для удаления")
                                }
                            }
                            putJsonArray("required") { add("task_id") }
                        }
                    }
                }
            }
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val result = when (toolName) {
            "get_project_status" -> getProjectStatus()
            "get_task" -> getTask(arguments)
            "list_tasks" -> listTasks(arguments)
            "create_task" -> createTask(arguments)
            "update_task_status" -> updateTaskStatus(arguments)
            "update_task_priority" -> updateTaskPriority(arguments)
            "assign_task" -> assignTask(arguments)
            "add_comment" -> addComment(arguments)
            "get_recommendations" -> getRecommendations(arguments)
            "get_team_workload" -> getTeamWorkload()
            "delete_task" -> deleteTask(arguments)
            else -> "Неизвестный инструмент: $toolName"
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
    }

    // ==================== Tool Implementations ====================

    private fun getProjectStatus(): String {
        val status = dataManager.getProjectStatus()
        return dataManager.formatProjectStatusForLlm(status)
    }

    private fun getTask(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"

        val task = dataManager.getTaskById(taskId)
            ?: return "Задача с ID '$taskId' не найдена"

        return dataManager.formatTaskForLlm(task)
    }

    private fun listTasks(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.content
        val statusStr = arguments["status"]?.jsonPrimitive?.content
        val priorityStr = arguments["priority"]?.jsonPrimitive?.content
        val typeStr = arguments["type"]?.jsonPrimitive?.content
        val assigneeId = arguments["assignee_id"]?.jsonPrimitive?.content
        val sprintId = arguments["sprint_id"]?.jsonPrimitive?.content

        val status = statusStr?.let { parseTaskStatus(it) }
        val priority = priorityStr?.let { parseTaskPriority(it) }
        val type = typeStr?.let { parseTaskType(it) }

        val tasks = dataManager.searchTasks(
            query = query,
            status = status,
            priority = priority,
            type = type,
            assigneeId = assigneeId,
            sprintId = sprintId
        )

        return dataManager.formatTasksListForLlm(tasks)
    }

    private fun createTask(arguments: JsonObject): String {
        val title = arguments["title"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'title' обязателен"
        val description = arguments["description"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'description' обязателен"
        val priorityStr = arguments["priority"]?.jsonPrimitive?.content ?: "medium"
        val typeStr = arguments["type"]?.jsonPrimitive?.content ?: "feature"
        val reporterId = arguments["reporter_id"]?.jsonPrimitive?.content ?: "pm_1"
        val assigneeId = arguments["assignee_id"]?.jsonPrimitive?.content
        val sprintId = arguments["sprint_id"]?.jsonPrimitive?.content
        val labels = arguments["labels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val storyPoints = arguments["story_points"]?.jsonPrimitive?.intOrNull

        val priority = parseTaskPriority(priorityStr)
            ?: return "Ошибка: неверное значение приоритета '$priorityStr'"
        val type = parseTaskType(typeStr)
            ?: return "Ошибка: неверное значение типа '$typeStr'"

        val task = dataManager.createTask(
            title = title,
            description = description,
            priority = priority,
            type = type,
            reporterId = reporterId,
            assigneeId = assigneeId,
            sprintId = sprintId,
            labels = labels,
            storyPoints = storyPoints
        )

        return buildString {
            appendLine("Задача успешно создана!")
            appendLine()
            appendLine(dataManager.formatTaskForLlm(task))
        }
    }

    private fun updateTaskStatus(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"
        val statusStr = arguments["status"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'status' обязателен"

        val status = parseTaskStatus(statusStr)
            ?: return "Ошибка: неверное значение статуса '$statusStr'"

        val task = dataManager.updateTaskStatus(taskId, status)
            ?: return "Задача с ID '$taskId' не найдена"

        return buildString {
            appendLine("Статус задачи обновлён!")
            appendLine()
            appendLine(dataManager.formatTaskForLlm(task))
        }
    }

    private fun updateTaskPriority(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"
        val priorityStr = arguments["priority"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'priority' обязателен"

        val priority = parseTaskPriority(priorityStr)
            ?: return "Ошибка: неверное значение приоритета '$priorityStr'"

        val task = dataManager.updateTaskPriority(taskId, priority)
            ?: return "Задача с ID '$taskId' не найдена"

        return buildString {
            appendLine("Приоритет задачи изменён!")
            appendLine()
            appendLine(dataManager.formatTaskForLlm(task))
        }
    }

    private fun assignTask(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"
        val assigneeId = arguments["assignee_id"]?.jsonPrimitive?.content

        // Проверяем что исполнитель существует
        if (assigneeId != null && dataManager.getMemberById(assigneeId) == null) {
            return "Ошибка: участник с ID '$assigneeId' не найден"
        }

        val task = dataManager.assignTask(taskId, assigneeId)
            ?: return "Задача с ID '$taskId' не найдена"

        val action = if (assigneeId != null) "назначена на ${dataManager.getMemberById(assigneeId)?.name}" else "снято назначение"
        return buildString {
            appendLine("Задача $action!")
            appendLine()
            appendLine(dataManager.formatTaskForLlm(task))
        }
    }

    private fun addComment(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'content' обязателен"
        val authorId = arguments["author_id"]?.jsonPrimitive?.content ?: "assistant"

        val task = dataManager.addTaskComment(taskId, authorId, content)
            ?: return "Задача с ID '$taskId' не найдена"

        return buildString {
            appendLine("Комментарий добавлен!")
            appendLine()
            appendLine(dataManager.formatTaskForLlm(task))
        }
    }

    private fun getRecommendations(arguments: JsonObject): String {
        val context = arguments["context"]?.jsonPrimitive?.content

        val status = dataManager.getProjectStatus()
        val criticalTasks = dataManager.getTasksByPriority(TaskPriority.CRITICAL)
            .filter { it.status != TaskStatus.DONE }
        val highTasks = dataManager.getTasksByPriority(TaskPriority.HIGH)
            .filter { it.status != TaskStatus.DONE }
        val blockedTasks = dataManager.getAllTasks()
            .filter { it.blockedBy.isNotEmpty() && it.status != TaskStatus.DONE }

        return buildString {
            appendLine("=== Рекомендации по приоритетам ===")
            appendLine()

            // CRITICAL задачи
            if (criticalTasks.isNotEmpty()) {
                appendLine("СРОЧНО (CRITICAL):")
                criticalTasks.forEach { task ->
                    appendLine("  - ${task.id}: ${task.title}")
                    appendLine("    Статус: ${task.status.name}, Исполнитель: ${task.assigneeId ?: "не назначен"}")
                }
                appendLine()
                appendLine(">>> Рекомендация: Критические задачи требуют немедленного внимания!")
                if (criticalTasks.any { it.assigneeId == null }) {
                    appendLine(">>> Есть незначенные критические задачи - назначьте исполнителей!")
                }
                appendLine()
            }

            // HIGH задачи
            if (highTasks.isNotEmpty()) {
                appendLine("ВЫСОКИЙ ПРИОРИТЕТ (HIGH):")
                highTasks.take(5).forEach { task ->
                    appendLine("  - ${task.id}: ${task.title}")
                }
                if (highTasks.size > 5) {
                    appendLine("  ... и ещё ${highTasks.size - 5}")
                }
                appendLine()
            }

            // Заблокированные задачи
            if (blockedTasks.isNotEmpty()) {
                appendLine("ЗАБЛОКИРОВАННЫЕ ЗАДАЧИ:")
                blockedTasks.forEach { task ->
                    appendLine("  - ${task.id}: ${task.title}")
                    appendLine("    Заблокирована: ${task.blockedBy.joinToString(", ")}")
                }
                appendLine()
                appendLine(">>> Рекомендация: Разблокируйте зависимости для продолжения работы")
                appendLine()
            }

            // Общие рекомендации
            appendLine("=== Порядок действий ===")
            appendLine()
            appendLine("1. Решить критические баги (${criticalTasks.size})")
            appendLine("2. Завершить задачи на code review")
            appendLine("3. Разблокировать заблокированные задачи (${blockedTasks.size})")
            appendLine("4. Работать над HIGH приоритетом (${highTasks.size})")
            appendLine()

            // Спринт прогресс
            if (status.activeSprint != null && status.sprintProgress != null) {
                val progress = status.sprintProgress
                val completion = progress.done * 100 / maxOf(progress.total, 1)
                appendLine("Прогресс спринта: $completion% (${progress.done}/${progress.total})")
                if (completion < 50 && progress.inProgress < 2) {
                    appendLine(">>> Спринт отстаёт! Нужно ускориться.")
                }
            }

            if (context != null) {
                appendLine()
                appendLine("Дополнительный контекст: $context")
            }
        }
    }

    private fun getTeamWorkload(): String {
        val members = dataManager.getAllMembers()

        return buildString {
            appendLine("=== Загрузка команды ===")
            appendLine()
            members.forEach { member ->
                val workload = dataManager.getMemberWorkload(member.id)
                val bar = "█".repeat(workload.coerceAtMost(10)) + "░".repeat((10 - workload).coerceAtLeast(0))
                appendLine("${member.name} (${member.role})")
                appendLine("  [$bar] $workload задач в работе")
                appendLine("  Навыки: ${member.skills.joinToString(", ")}")
                appendLine()
            }
        }
    }

    private fun deleteTask(arguments: JsonObject): String {
        val taskId = arguments["task_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'task_id' обязателен"

        // Проверяем что задача существует
        val task = dataManager.getTaskById(taskId)
            ?: return "Задача с ID '$taskId' не найдена"

        // Удаляем задачу
        val deleted = dataManager.deleteTask(taskId)

        return if (deleted) {
            "Задача '${task.title}' (${task.id}) успешно удалена."
        } else {
            "Ошибка при удалении задачи '$taskId'"
        }
    }

    // ==================== Helpers ====================

    private fun parseTaskStatus(value: String): TaskStatus? {
        return when (value.lowercase()) {
            "backlog" -> TaskStatus.BACKLOG
            "todo" -> TaskStatus.TODO
            "in_progress" -> TaskStatus.IN_PROGRESS
            "review" -> TaskStatus.REVIEW
            "testing" -> TaskStatus.TESTING
            "done" -> TaskStatus.DONE
            else -> null
        }
    }

    private fun parseTaskPriority(value: String): TaskPriority? {
        return when (value.lowercase()) {
            "low" -> TaskPriority.LOW
            "medium" -> TaskPriority.MEDIUM
            "high" -> TaskPriority.HIGH
            "critical" -> TaskPriority.CRITICAL
            else -> null
        }
    }

    private fun parseTaskType(value: String): TaskType? {
        return when (value.lowercase()) {
            "feature" -> TaskType.FEATURE
            "bug" -> TaskType.BUG
            "tech_debt" -> TaskType.TECH_DEBT
            "spike" -> TaskType.SPIKE
            "improvement" -> TaskType.IMPROVEMENT
            else -> null
        }
    }
}

fun main() {
    TasksMcpServer().run()
}
