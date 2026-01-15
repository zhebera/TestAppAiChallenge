package org.example.data.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Таблица команды разработки.
 */
object TeamMembersTable : Table("team_members") {
    val id = varchar("id", 36)  // UUID
    val name = varchar("name", 255)
    val email = varchar("email", 255).nullable()
    val role = varchar("role", 50)  // developer, qa, designer, pm, etc.
    val skills = text("skills")  // JSON array: ["kotlin", "android", "testing"]
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица спринтов.
 */
object SprintsTable : Table("sprints") {
    val id = varchar("id", 36)  // UUID
    val name = varchar("name", 255)
    val startDate = varchar("start_date", 10)  // YYYY-MM-DD
    val endDate = varchar("end_date", 10)  // YYYY-MM-DD
    val goal = text("goal").nullable()
    val status = varchar("status", 20)  // planning, active, completed
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица задач проекта.
 */
object TasksTable : Table("tasks") {
    val id = varchar("id", 36)  // UUID
    val title = varchar("title", 500)
    val description = text("description")
    val status = varchar("status", 20)  // backlog, todo, in_progress, review, testing, done
    val priority = varchar("priority", 20)  // low, medium, high, critical
    val type = varchar("type", 20)  // feature, bug, tech_debt, spike, improvement
    val assigneeId = varchar("assignee_id", 36).references(TeamMembersTable.id).nullable()
    val reporterId = varchar("reporter_id", 36).references(TeamMembersTable.id).nullable()
    val sprintId = varchar("sprint_id", 36).references(SprintsTable.id).nullable()
    val storyPoints = integer("story_points").nullable()
    val dueDate = varchar("due_date", 10).nullable()  // YYYY-MM-DD
    val labels = text("labels")  // JSON array: ["backend", "api", "urgent"]
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица комментариев к задачам.
 */
object TaskCommentsTable : Table("task_comments") {
    val id = varchar("id", 36)  // UUID
    val taskId = varchar("task_id", 36).references(TasksTable.id)
    val authorId = varchar("author_id", 36).references(TeamMembersTable.id).nullable()
    val content = text("content")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица блокировок между задачами (зависимости).
 */
object TaskBlockersTable : Table("task_blockers") {
    val taskId = varchar("task_id", 36).references(TasksTable.id)
    val blockedByTaskId = varchar("blocked_by_task_id", 36).references(TasksTable.id)

    override val primaryKey = PrimaryKey(taskId, blockedByTaskId)
}
