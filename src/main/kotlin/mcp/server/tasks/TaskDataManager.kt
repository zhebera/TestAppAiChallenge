package org.example.mcp.server.tasks

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.SprintsTable
import org.example.data.persistence.TaskBlockersTable
import org.example.data.persistence.TaskCommentsTable
import org.example.data.persistence.TasksTable
import org.example.data.persistence.TeamMembersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Менеджер данных для системы задач проекта.
 * Использует SQLite + Exposed для хранения данных.
 */
class TaskDataManager {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    init {
        // Убедимся что БД инициализирована
        DatabaseConfig.init()

        // Инициализируем демо-данные если таблица пуста
        transaction {
            if (TasksTable.selectAll().empty()) {
                initializeDemoData()
            }
        }
    }

    // ==================== Tasks ====================

    fun getAllTasks(): List<ProjectTask> = transaction {
        TasksTable.selectAll().map { it.toProjectTask() }
    }

    fun getTaskById(taskId: String): ProjectTask? = transaction {
        TasksTable.selectAll().where { TasksTable.id eq taskId }
            .map { it.toProjectTask() }
            .singleOrNull()
    }

    fun getTasksByStatus(status: TaskStatus): List<ProjectTask> = transaction {
        TasksTable.selectAll().where { TasksTable.status eq status.name.lowercase() }
            .map { it.toProjectTask() }
    }

    fun getTasksByPriority(priority: TaskPriority): List<ProjectTask> = transaction {
        TasksTable.selectAll().where { TasksTable.priority eq priority.name.lowercase() }
            .map { it.toProjectTask() }
    }

    fun getTasksByAssignee(assigneeId: String): List<ProjectTask> = transaction {
        TasksTable.selectAll().where { TasksTable.assigneeId eq assigneeId }
            .map { it.toProjectTask() }
    }

    fun getTasksBySprint(sprintId: String): List<ProjectTask> = transaction {
        TasksTable.selectAll().where { TasksTable.sprintId eq sprintId }
            .map { it.toProjectTask() }
    }

    fun searchTasks(
        query: String? = null,
        status: TaskStatus? = null,
        priority: TaskPriority? = null,
        type: TaskType? = null,
        assigneeId: String? = null,
        sprintId: String? = null
    ): List<ProjectTask> = transaction {
        val conditions = mutableListOf<Op<Boolean>>()

        status?.let { conditions.add(TasksTable.status eq it.name.lowercase()) }
        priority?.let { conditions.add(TasksTable.priority eq it.name.lowercase()) }
        type?.let { conditions.add(TasksTable.type eq it.name.lowercase()) }
        assigneeId?.let { conditions.add(TasksTable.assigneeId eq it) }
        sprintId?.let { conditions.add(TasksTable.sprintId eq it) }

        val baseQuery = if (conditions.isEmpty()) {
            TasksTable.selectAll()
        } else {
            TasksTable.selectAll().where { conditions.reduce { acc, op -> acc and op } }
        }

        val tasks = baseQuery.map { it.toProjectTask() }

        // Фильтруем по текстовому запросу в памяти (для поддержки частичного совпадения)
        if (query != null) {
            tasks.filter { task ->
                task.title.contains(query, ignoreCase = true) ||
                task.description.contains(query, ignoreCase = true) ||
                task.labels.any { it.contains(query, ignoreCase = true) }
            }
        } else {
            tasks
        }
    }

    fun createTask(
        title: String,
        description: String,
        priority: TaskPriority,
        type: TaskType,
        reporterId: String,
        assigneeId: String? = null,
        sprintId: String? = null,
        labels: List<String> = emptyList(),
        storyPoints: Int? = null,
        dueDate: String? = null
    ): ProjectTask = transaction {
        val now = System.currentTimeMillis()
        val taskId = "task_${UUID.randomUUID().toString().take(8)}"
        val statusValue = if (sprintId != null) TaskStatus.TODO else TaskStatus.BACKLOG

        TasksTable.insert {
            it[id] = taskId
            it[TasksTable.title] = title
            it[TasksTable.description] = description
            it[status] = statusValue.name.lowercase()
            it[TasksTable.priority] = priority.name.lowercase()
            it[TasksTable.type] = type.name.lowercase()
            it[TasksTable.assigneeId] = assigneeId
            it[TasksTable.reporterId] = reporterId
            it[TasksTable.sprintId] = sprintId
            it[TasksTable.storyPoints] = storyPoints
            it[TasksTable.dueDate] = dueDate
            it[TasksTable.labels] = json.encodeToString(labels)
            it[createdAt] = now
            it[updatedAt] = now
        }

        getTaskById(taskId)!!
    }

    fun updateTaskStatus(taskId: String, status: TaskStatus): ProjectTask? = transaction {
        val now = System.currentTimeMillis()
        val updated = TasksTable.update({ TasksTable.id eq taskId }) {
            it[TasksTable.status] = status.name.lowercase()
            it[updatedAt] = now
        }
        if (updated > 0) getTaskById(taskId) else null
    }

    fun updateTaskPriority(taskId: String, priority: TaskPriority): ProjectTask? = transaction {
        val now = System.currentTimeMillis()
        val updated = TasksTable.update({ TasksTable.id eq taskId }) {
            it[TasksTable.priority] = priority.name.lowercase()
            it[updatedAt] = now
        }
        if (updated > 0) getTaskById(taskId) else null
    }

    fun assignTask(taskId: String, assigneeId: String?): ProjectTask? = transaction {
        val now = System.currentTimeMillis()
        val updated = TasksTable.update({ TasksTable.id eq taskId }) {
            it[TasksTable.assigneeId] = assigneeId
            it[updatedAt] = now
        }
        if (updated > 0) getTaskById(taskId) else null
    }

    fun addTaskComment(taskId: String, authorId: String, content: String): ProjectTask? = transaction {
        // Проверяем что задача существует
        val task = getTaskById(taskId) ?: return@transaction null

        val commentId = "comment_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        TaskCommentsTable.insert {
            it[id] = commentId
            it[TaskCommentsTable.taskId] = taskId
            it[TaskCommentsTable.authorId] = authorId
            it[TaskCommentsTable.content] = content
            it[timestamp] = now
        }

        TasksTable.update({ TasksTable.id eq taskId }) {
            it[updatedAt] = now
        }

        getTaskById(taskId)
    }

    fun deleteTask(taskId: String): Boolean = transaction {
        // Удаляем комментарии
        TaskCommentsTable.deleteWhere { TaskCommentsTable.taskId eq taskId }
        // Удаляем блокировки
        TaskBlockersTable.deleteWhere { TaskBlockersTable.taskId eq taskId }
        TaskBlockersTable.deleteWhere { blockedByTaskId eq taskId }
        // Удаляем задачу
        TasksTable.deleteWhere { id eq taskId } > 0
    }

    // ==================== Team Members ====================

    fun getAllMembers(): List<TeamMember> = transaction {
        TeamMembersTable.selectAll().map { it.toTeamMember() }
    }

    fun getMemberById(memberId: String): TeamMember? = transaction {
        TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }
            .map { it.toTeamMember() }
            .singleOrNull()
    }

    fun getMemberWorkload(memberId: String): Int = transaction {
        TasksTable.selectAll().where {
            (TasksTable.assigneeId eq memberId) and
            (TasksTable.status inList listOf("todo", "in_progress", "review"))
        }.count().toInt()
    }

    fun createMember(
        name: String,
        email: String?,
        role: String,
        skills: List<String> = emptyList()
    ): TeamMember = transaction {
        val memberId = "member_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        TeamMembersTable.insert {
            it[id] = memberId
            it[TeamMembersTable.name] = name
            it[TeamMembersTable.email] = email
            it[TeamMembersTable.role] = role
            it[TeamMembersTable.skills] = json.encodeToString(skills)
            it[createdAt] = now
        }

        getMemberById(memberId)!!
    }

    // ==================== Sprints ====================

    fun getAllSprints(): List<Sprint> = transaction {
        SprintsTable.selectAll().map { it.toSprint() }
    }

    fun getActiveSprint(): Sprint? = transaction {
        SprintsTable.selectAll().where { SprintsTable.status eq "active" }
            .map { it.toSprint() }
            .singleOrNull()
    }

    fun getSprintById(sprintId: String): Sprint? = transaction {
        SprintsTable.selectAll().where { SprintsTable.id eq sprintId }
            .map { it.toSprint() }
            .singleOrNull()
    }

    fun createSprint(
        name: String,
        startDate: String,
        endDate: String,
        goal: String? = null,
        status: SprintStatus = SprintStatus.PLANNED
    ): Sprint = transaction {
        val sprintId = "sprint_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        SprintsTable.insert {
            it[id] = sprintId
            it[SprintsTable.name] = name
            it[SprintsTable.startDate] = startDate
            it[SprintsTable.endDate] = endDate
            it[SprintsTable.goal] = goal
            it[SprintsTable.status] = status.name.lowercase()
            it[createdAt] = now
        }

        getSprintById(sprintId)!!
    }

    fun updateSprintStatus(sprintId: String, status: SprintStatus): Sprint? = transaction {
        val updated = SprintsTable.update({ SprintsTable.id eq sprintId }) {
            it[SprintsTable.status] = status.name.lowercase()
        }
        if (updated > 0) getSprintById(sprintId) else null
    }

    // ==================== Task Blockers ====================

    fun addBlocker(taskId: String, blockedByTaskId: String): Boolean = transaction {
        try {
            TaskBlockersTable.insert {
                it[TaskBlockersTable.taskId] = taskId
                it[TaskBlockersTable.blockedByTaskId] = blockedByTaskId
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun removeBlocker(taskId: String, blockedByTaskId: String): Boolean = transaction {
        TaskBlockersTable.deleteWhere {
            (TaskBlockersTable.taskId eq taskId) and
            (TaskBlockersTable.blockedByTaskId eq blockedByTaskId)
        } > 0
    }

    fun getBlockersForTask(taskId: String): List<String> = transaction {
        TaskBlockersTable.selectAll().where { TaskBlockersTable.taskId eq taskId }
            .map { it[TaskBlockersTable.blockedByTaskId] }
    }

    // ==================== Project Status ====================

    fun getProjectStatus(): ProjectStatus = transaction {
        val allTasks = getAllTasks()
        val activeSprint = getActiveSprint()
        val sprintTasks = if (activeSprint != null) {
            getTasksBySprint(activeSprint.id)
        } else {
            emptyList()
        }

        val totalTasks = allTasks.size
        val doneTasks = allTasks.count { it.status == TaskStatus.DONE }
        val inProgressTasks = allTasks.count { it.status == TaskStatus.IN_PROGRESS }
        val blockedTasks = allTasks.count { it.blockedBy.isNotEmpty() }
        val criticalTasks = allTasks.count { it.priority == TaskPriority.CRITICAL && it.status != TaskStatus.DONE }
        val highPriorityTasks = allTasks.count { it.priority == TaskPriority.HIGH && it.status != TaskStatus.DONE }

        ProjectStatus(
            totalTasks = totalTasks,
            doneTasks = doneTasks,
            inProgressTasks = inProgressTasks,
            blockedTasks = blockedTasks,
            criticalTasks = criticalTasks,
            highPriorityTasks = highPriorityTasks,
            activeSprint = activeSprint,
            sprintProgress = if (sprintTasks.isNotEmpty()) {
                SprintProgress(
                    total = sprintTasks.size,
                    done = sprintTasks.count { it.status == TaskStatus.DONE },
                    inProgress = sprintTasks.count { it.status == TaskStatus.IN_PROGRESS },
                    todo = sprintTasks.count { it.status == TaskStatus.TODO }
                )
            } else null
        )
    }

    // ==================== Formatting for LLM ====================

    fun formatTaskForLlm(task: ProjectTask): String {
        val assignee = task.assigneeId?.let { getMemberById(it)?.name } ?: "не назначен"
        val sprint = task.sprintId?.let { getSprintById(it)?.name } ?: "без спринта"

        return buildString {
            appendLine("Задача #${task.id}")
            appendLine("Заголовок: ${task.title}")
            appendLine("Описание: ${task.description}")
            appendLine("Статус: ${formatStatus(task.status)}")
            appendLine("Приоритет: ${formatPriority(task.priority)}")
            appendLine("Тип: ${formatType(task.type)}")
            appendLine("Исполнитель: $assignee")
            appendLine("Спринт: $sprint")
            if (task.storyPoints != null) {
                appendLine("Story Points: ${task.storyPoints}")
            }
            if (task.labels.isNotEmpty()) {
                appendLine("Метки: ${task.labels.joinToString(", ")}")
            }
            if (task.dueDate != null) {
                appendLine("Срок: ${task.dueDate}")
            }
            if (task.blockedBy.isNotEmpty()) {
                appendLine("Заблокирована: ${task.blockedBy.joinToString(", ")}")
            }
            appendLine("Создана: ${task.createdAt}")
            appendLine("Обновлена: ${task.updatedAt}")
            if (task.comments.isNotEmpty()) {
                appendLine("Комментарии (${task.comments.size}):")
                task.comments.takeLast(3).forEach { comment ->
                    val author = getMemberById(comment.authorId)?.name ?: comment.authorId
                    appendLine("  - $author: ${comment.content}")
                }
            }
        }
    }

    fun formatTasksListForLlm(tasks: List<ProjectTask>): String {
        if (tasks.isEmpty()) return "Задачи не найдены"

        return buildString {
            appendLine("Найдено задач: ${tasks.size}")
            appendLine()
            tasks.forEach { task ->
                val assignee = task.assigneeId?.let { getMemberById(it)?.name } ?: "-"
                appendLine("${task.id} | ${formatPriority(task.priority)} | ${formatStatus(task.status)} | $assignee")
                appendLine("  ${task.title}")
                appendLine()
            }
        }
    }

    fun formatProjectStatusForLlm(status: ProjectStatus): String {
        return buildString {
            appendLine("=== Статус проекта ===")
            appendLine()
            appendLine("Общая статистика:")
            appendLine("  Всего задач: ${status.totalTasks}")
            appendLine("  Выполнено: ${status.doneTasks} (${(status.doneTasks * 100 / maxOf(status.totalTasks, 1))}%)")
            appendLine("  В работе: ${status.inProgressTasks}")
            appendLine("  Заблокировано: ${status.blockedTasks}")
            appendLine()
            appendLine("Требуют внимания:")
            appendLine("  CRITICAL: ${status.criticalTasks}")
            appendLine("  HIGH: ${status.highPriorityTasks}")

            if (status.activeSprint != null && status.sprintProgress != null) {
                appendLine()
                appendLine("Активный спринт: ${status.activeSprint.name}")
                appendLine("  Цель: ${status.activeSprint.goal ?: "не указана"}")
                appendLine("  Прогресс: ${status.sprintProgress.done}/${status.sprintProgress.total}")
                appendLine("  В работе: ${status.sprintProgress.inProgress}")
                appendLine("  TODO: ${status.sprintProgress.todo}")
            }
        }
    }

    /**
     * Детальное форматирование задачи (алиас для formatTaskForLlm).
     */
    fun formatTaskDetailForLlm(task: ProjectTask): String = formatTaskForLlm(task)

    /**
     * Форматирование загрузки команды.
     */
    fun formatTeamWorkloadForLlm(): String {
        val members = getAllMembers()
        return buildString {
            appendLine("=== Загрузка команды ===")
            appendLine()
            members.forEach { member ->
                val tasks = getTasksByAssignee(member.id).filter { it.status != TaskStatus.DONE }
                appendLine("${member.name} (${member.role}) - ${tasks.size} активных задач")
                if (tasks.isNotEmpty()) {
                    tasks.take(3).forEach { task ->
                        appendLine("  • ${task.id}: ${task.title} [${formatPriority(task.priority)}]")
                    }
                    if (tasks.size > 3) {
                        appendLine("  ... и ещё ${tasks.size - 3}")
                    }
                }
            }
        }
    }

    /**
     * Получить рекомендации по приоритетам.
     */
    fun getRecommendations(): String {
        val allTasks = getAllTasks().filter { it.status != TaskStatus.DONE }

        val criticalTasks = allTasks.filter { it.priority == TaskPriority.CRITICAL }
        val highTasks = allTasks.filter { it.priority == TaskPriority.HIGH }
        val blockedTasks = allTasks.filter { it.blockedBy.isNotEmpty() }
        val unassignedHighPriority = allTasks.filter {
            it.assigneeId == null && it.priority in listOf(TaskPriority.CRITICAL, TaskPriority.HIGH)
        }

        return buildString {
            appendLine("=== Рекомендации по приоритетам ===")
            appendLine()

            if (criticalTasks.isNotEmpty()) {
                appendLine("🔴 CRITICAL задачи (делать НЕМЕДЛЕННО):")
                criticalTasks.forEach { task ->
                    val assignee = task.assigneeId?.let { getMemberById(it)?.name } ?: "НЕ НАЗНАЧЕН"
                    appendLine("  • ${task.id}: ${task.title} [$assignee]")
                }
                appendLine()
            }

            if (highTasks.isNotEmpty()) {
                appendLine("🟠 HIGH задачи (приоритет на сегодня):")
                highTasks.take(5).forEach { task ->
                    val assignee = task.assigneeId?.let { getMemberById(it)?.name } ?: "не назначен"
                    appendLine("  • ${task.id}: ${task.title} [$assignee]")
                }
                appendLine()
            }

            if (blockedTasks.isNotEmpty()) {
                appendLine("⛔ Заблокированные задачи (требуют разблокировки):")
                blockedTasks.forEach { task ->
                    appendLine("  • ${task.id}: ${task.title}")
                    appendLine("    Блокеры: ${task.blockedBy.joinToString(", ")}")
                }
                appendLine()
            }

            if (unassignedHighPriority.isNotEmpty()) {
                appendLine("⚠️ Важные задачи без исполнителя:")
                unassignedHighPriority.forEach { task ->
                    appendLine("  • ${task.id}: ${task.title} [${formatPriority(task.priority)}]")
                }
                appendLine()
            }

            if (criticalTasks.isEmpty() && highTasks.isEmpty() && blockedTasks.isEmpty()) {
                appendLine("✅ Нет срочных задач! Можно работать над задачами средней важности.")
            }
        }
    }

    private fun formatStatus(status: TaskStatus): String = when (status) {
        TaskStatus.BACKLOG -> "Бэклог"
        TaskStatus.TODO -> "К выполнению"
        TaskStatus.IN_PROGRESS -> "В работе"
        TaskStatus.REVIEW -> "На ревью"
        TaskStatus.TESTING -> "Тестирование"
        TaskStatus.DONE -> "Выполнено"
    }

    private fun formatPriority(priority: TaskPriority): String = when (priority) {
        TaskPriority.LOW -> "LOW"
        TaskPriority.MEDIUM -> "MEDIUM"
        TaskPriority.HIGH -> "HIGH"
        TaskPriority.CRITICAL -> "CRITICAL"
    }

    private fun formatType(type: TaskType): String = when (type) {
        TaskType.FEATURE -> "Фича"
        TaskType.BUG -> "Баг"
        TaskType.TECH_DEBT -> "Тех. долг"
        TaskType.SPIKE -> "Исследование"
        TaskType.IMPROVEMENT -> "Улучшение"
    }

    // ==================== Row Mappers ====================

    private fun ResultRow.toProjectTask(): ProjectTask {
        val taskId = this[TasksTable.id]
        val comments = getCommentsForTask(taskId)
        val blockers = getBlockersForTask(taskId)

        val createdAtTimestamp = this[TasksTable.createdAt]
        val updatedAtTimestamp = this[TasksTable.updatedAt]

        return ProjectTask(
            id = taskId,
            title = this[TasksTable.title],
            description = this[TasksTable.description],
            status = TaskStatus.valueOf(this[TasksTable.status].uppercase()),
            priority = TaskPriority.valueOf(this[TasksTable.priority].uppercase()),
            type = TaskType.valueOf(this[TasksTable.type].uppercase()),
            assigneeId = this[TasksTable.assigneeId],
            reporterId = this[TasksTable.reporterId] ?: "system",
            sprintId = this[TasksTable.sprintId],
            labels = try {
                json.decodeFromString<List<String>>(this[TasksTable.labels])
            } catch (e: Exception) {
                emptyList()
            },
            storyPoints = this[TasksTable.storyPoints],
            createdAt = formatTimestamp(createdAtTimestamp),
            updatedAt = formatTimestamp(updatedAtTimestamp),
            dueDate = this[TasksTable.dueDate],
            comments = comments,
            blockedBy = blockers
        )
    }

    private fun getCommentsForTask(taskId: String): List<TaskComment> {
        return TaskCommentsTable.selectAll().where { TaskCommentsTable.taskId eq taskId }
            .orderBy(TaskCommentsTable.timestamp)
            .map { row ->
                TaskComment(
                    id = row[TaskCommentsTable.id],
                    authorId = row[TaskCommentsTable.authorId] ?: "unknown",
                    content = row[TaskCommentsTable.content],
                    timestamp = formatTimestamp(row[TaskCommentsTable.timestamp])
                )
            }
    }

    private fun ResultRow.toTeamMember(): TeamMember {
        val workload = getMemberWorkload(this[TeamMembersTable.id])
        return TeamMember(
            id = this[TeamMembersTable.id],
            name = this[TeamMembersTable.name],
            email = this[TeamMembersTable.email] ?: "",
            role = this[TeamMembersTable.role],
            skills = try {
                json.decodeFromString<List<String>>(this[TeamMembersTable.skills])
            } catch (e: Exception) {
                emptyList()
            },
            currentWorkload = workload
        )
    }

    private fun ResultRow.toSprint(): Sprint {
        return Sprint(
            id = this[SprintsTable.id],
            name = this[SprintsTable.name],
            startDate = this[SprintsTable.startDate],
            endDate = this[SprintsTable.endDate],
            goal = this[SprintsTable.goal],
            status = SprintStatus.valueOf(this[SprintsTable.status].uppercase())
        )
    }

    private fun formatTimestamp(timestamp: Long): String {
        return LocalDateTime.ofEpochSecond(
            timestamp / 1000,
            ((timestamp % 1000) * 1_000_000).toInt(),
            java.time.ZoneOffset.UTC
        ).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    // ==================== Demo Data ====================

    private fun initializeDemoData() {
        val now = System.currentTimeMillis()

        // Создаём участников команды
        val members = listOf(
            Triple("dev_1", "Алексей Иванов", "lead"),
            Triple("dev_2", "Мария Петрова", "developer"),
            Triple("dev_3", "Дмитрий Сидоров", "developer"),
            Triple("qa_1", "Елена Козлова", "qa"),
            Triple("pm_1", "Сергей Николаев", "pm")
        )

        val skillsMap = mapOf(
            "dev_1" to listOf("kotlin", "android", "architecture"),
            "dev_2" to listOf("kotlin", "backend", "api"),
            "dev_3" to listOf("kotlin", "frontend", "ui"),
            "qa_1" to listOf("testing", "automation"),
            "pm_1" to listOf("management", "analytics")
        )

        val emailMap = mapOf(
            "dev_1" to "alexey@team.dev",
            "dev_2" to "maria@team.dev",
            "dev_3" to "dmitry@team.dev",
            "qa_1" to "elena@team.dev",
            "pm_1" to "sergey@team.dev"
        )

        members.forEach { (id, name, role) ->
            TeamMembersTable.insert {
                it[TeamMembersTable.id] = id
                it[TeamMembersTable.name] = name
                it[TeamMembersTable.email] = emailMap[id]
                it[TeamMembersTable.role] = role
                it[skills] = json.encodeToString(skillsMap[id] ?: emptyList<String>())
                it[createdAt] = now
            }
        }

        // Создаём спринт
        SprintsTable.insert {
            it[id] = "sprint_1"
            it[name] = "Sprint 23 - Team Assistant"
            it[startDate] = "2025-01-13"
            it[endDate] = "2025-01-27"
            it[goal] = "Реализовать командного ассистента с RAG и MCP интеграцией"
            it[status] = "active"
            it[createdAt] = now
        }

        // Создаём демо-задачи
        val tasks = listOf(
            mapOf(
                "id" to "task_001",
                "title" to "Реализовать MCP сервер для задач",
                "description" to "Создать MCP сервер с инструментами для управления задачами: создание, обновление статуса, поиск",
                "status" to "in_progress",
                "priority" to "high",
                "type" to "feature",
                "assignee_id" to "dev_1",
                "reporter_id" to "pm_1",
                "sprint_id" to "sprint_1",
                "labels" to listOf("mcp", "backend"),
                "story_points" to 5
            ),
            mapOf(
                "id" to "task_002",
                "title" to "Интеграция RAG с командным ассистентом",
                "description" to "Подключить RAG систему для поиска информации о проекте при ответах на вопросы",
                "status" to "todo",
                "priority" to "high",
                "type" to "feature",
                "assignee_id" to "dev_2",
                "reporter_id" to "pm_1",
                "sprint_id" to "sprint_1",
                "labels" to listOf("rag", "ai"),
                "story_points" to 3
            ),
            mapOf(
                "id" to "task_003",
                "title" to "Исправить утечку памяти в чат-клиенте",
                "description" to "При длительных сессиях наблюдается рост потребления памяти. Нужно профилировать и исправить",
                "status" to "backlog",
                "priority" to "critical",
                "type" to "bug",
                "assignee_id" to null,
                "reporter_id" to "qa_1",
                "sprint_id" to null,
                "labels" to listOf("bug", "memory", "performance"),
                "story_points" to 8
            ),
            mapOf(
                "id" to "task_004",
                "title" to "Добавить рекомендации по приоритетам",
                "description" to "Ассистент должен уметь анализировать задачи и предлагать, какие делать первыми",
                "status" to "todo",
                "priority" to "medium",
                "type" to "feature",
                "assignee_id" to "dev_1",
                "reporter_id" to "pm_1",
                "sprint_id" to "sprint_1",
                "labels" to listOf("ai", "assistant"),
                "story_points" to 5
            ),
            mapOf(
                "id" to "task_005",
                "title" to "Рефакторинг McpClient",
                "description" to "Упростить код MCP клиента, добавить retry логику и лучшую обработку ошибок",
                "status" to "backlog",
                "priority" to "low",
                "type" to "tech_debt",
                "assignee_id" to null,
                "reporter_id" to "dev_1",
                "sprint_id" to null,
                "labels" to listOf("refactoring", "mcp"),
                "story_points" to 3
            ),
            mapOf(
                "id" to "task_006",
                "title" to "UI для отображения статуса проекта",
                "description" to "Добавить консольный дашборд с визуализацией прогресса спринта",
                "status" to "backlog",
                "priority" to "medium",
                "type" to "feature",
                "assignee_id" to "dev_3",
                "reporter_id" to "pm_1",
                "sprint_id" to null,
                "labels" to listOf("ui", "dashboard"),
                "story_points" to 5
            ),
            mapOf(
                "id" to "task_007",
                "title" to "Написать документацию по API ассистента",
                "description" to "Создать README с описанием всех команд и примерами использования",
                "status" to "todo",
                "priority" to "low",
                "type" to "improvement",
                "assignee_id" to null,
                "reporter_id" to "pm_1",
                "sprint_id" to "sprint_1",
                "labels" to listOf("docs"),
                "story_points" to 2
            )
        )

        tasks.forEach { taskData ->
            TasksTable.insert {
                it[id] = taskData["id"] as String
                it[title] = taskData["title"] as String
                it[description] = taskData["description"] as String
                it[status] = taskData["status"] as String
                it[priority] = taskData["priority"] as String
                it[type] = taskData["type"] as String
                it[assigneeId] = taskData["assignee_id"] as? String
                it[reporterId] = taskData["reporter_id"] as? String
                it[sprintId] = taskData["sprint_id"] as? String
                @Suppress("UNCHECKED_CAST")
                it[labels] = json.encodeToString(taskData["labels"] as List<String>)
                it[storyPoints] = taskData["story_points"] as? Int
                it[dueDate] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
}

data class ProjectStatus(
    val totalTasks: Int,
    val doneTasks: Int,
    val inProgressTasks: Int,
    val blockedTasks: Int,
    val criticalTasks: Int,
    val highPriorityTasks: Int,
    val activeSprint: Sprint?,
    val sprintProgress: SprintProgress?
)

data class SprintProgress(
    val total: Int,
    val done: Int,
    val inProgress: Int,
    val todo: Int
)
