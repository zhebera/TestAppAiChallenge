package org.example.mcp.server.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модели данных для системы управления задачами проекта.
 * Отличается от CRM тикетов - это задачи команды разработки.
 */

@Serializable
data class TasksDatabase(
    val tasks: List<ProjectTask> = emptyList(),
    val members: List<TeamMember> = emptyList(),
    val sprints: List<Sprint> = emptyList()
)

@Serializable
data class ProjectTask(
    val id: String,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val type: TaskType,
    @SerialName("assignee_id")
    val assigneeId: String? = null,
    @SerialName("reporter_id")
    val reporterId: String,
    @SerialName("sprint_id")
    val sprintId: String? = null,
    val labels: List<String> = emptyList(),
    @SerialName("story_points")
    val storyPoints: Int? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("due_date")
    val dueDate: String? = null,
    val comments: List<TaskComment> = emptyList(),
    @SerialName("blocked_by")
    val blockedBy: List<String> = emptyList(),
    val subtasks: List<String> = emptyList()
)

@Serializable
enum class TaskStatus {
    @SerialName("backlog")
    BACKLOG,
    @SerialName("todo")
    TODO,
    @SerialName("in_progress")
    IN_PROGRESS,
    @SerialName("review")
    REVIEW,
    @SerialName("testing")
    TESTING,
    @SerialName("done")
    DONE
}

@Serializable
enum class TaskPriority {
    @SerialName("low")
    LOW,
    @SerialName("medium")
    MEDIUM,
    @SerialName("high")
    HIGH,
    @SerialName("critical")
    CRITICAL
}

@Serializable
enum class TaskType {
    @SerialName("feature")
    FEATURE,
    @SerialName("bug")
    BUG,
    @SerialName("tech_debt")
    TECH_DEBT,
    @SerialName("spike")
    SPIKE,
    @SerialName("improvement")
    IMPROVEMENT
}

@Serializable
data class TaskComment(
    val id: String,
    @SerialName("author_id")
    val authorId: String,
    val content: String,
    val timestamp: String
)

@Serializable
data class TeamMember(
    val id: String,
    val name: String,
    val email: String,
    val role: String,  // developer, lead, qa, pm
    val skills: List<String> = emptyList(),
    @SerialName("current_workload")
    val currentWorkload: Int = 0  // количество задач в работе
)

@Serializable
data class Sprint(
    val id: String,
    val name: String,
    @SerialName("start_date")
    val startDate: String,
    @SerialName("end_date")
    val endDate: String,
    val goal: String? = null,
    val status: SprintStatus
)

@Serializable
enum class SprintStatus {
    @SerialName("planned")
    PLANNED,
    @SerialName("active")
    ACTIVE,
    @SerialName("completed")
    COMPLETED
}
