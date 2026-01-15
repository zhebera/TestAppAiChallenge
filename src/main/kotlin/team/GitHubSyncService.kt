package org.example.team

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.example.data.mcp.McpClient
import org.example.mcp.server.tasks.TaskDataManager
import org.example.mcp.server.tasks.TaskPriority
import org.example.mcp.server.tasks.TaskStatus
import org.example.mcp.server.tasks.TaskType

/**
 * Сервис синхронизации задач с GitHub Issues.
 *
 * Функции:
 * - Импорт Issues из GitHub как задач
 * - Экспорт задач в GitHub Issues
 * - Синхронизация статусов
 */
class GitHubSyncService(
    private val githubMcpClient: McpClient?,
    private val taskDataManager: TaskDataManager,
    private val json: Json
) {
    /**
     * Импортировать Issues из GitHub репозитория.
     * @param owner владелец репозитория
     * @param repo название репозитория
     * @param state фильтр состояния: open, closed, all
     * @param labels фильтр по меткам
     */
    suspend fun importIssues(
        owner: String,
        repo: String,
        state: String = "open",
        labels: List<String>? = null
    ): ImportResult {
        if (githubMcpClient == null) {
            return ImportResult.Error("GitHub MCP не подключен")
        }

        return try {
            // Получаем issues через GitHub API (MCP)
            val args = mutableMapOf<String, JsonPrimitive>(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "state" to JsonPrimitive(state)
            )

            val result = githubMcpClient.callTool("list_issues", args)
            val issuesText = result.content.firstOrNull()?.text ?: ""

            // Парсим issues из текстового ответа
            val importedTasks = mutableListOf<String>()
            val skippedIssues = mutableListOf<String>()

            // Простой парсинг - ищем паттерны #number и title
            val issuePattern = Regex("""#(\d+)\s*[:-]?\s*(.+?)(?=\n#|\n\n|$)""", RegexOption.DOT_MATCHES_ALL)
            val matches = issuePattern.findAll(issuesText)

            for (match in matches) {
                val issueNumber = match.groupValues[1]
                val title = match.groupValues[2].trim().lines().firstOrNull()?.trim() ?: continue

                // Проверяем существует ли уже задача с таким issue
                val existingTask = taskDataManager.getAllTasks().find {
                    it.labels.contains("github:$owner/$repo#$issueNumber")
                }

                if (existingTask != null) {
                    skippedIssues.add("#$issueNumber (уже импортирована как ${existingTask.id})")
                    continue
                }

                // Определяем приоритет по меткам
                val priority = determinePriorityFromLabels(labels ?: emptyList())
                val type = determineTypeFromLabels(labels ?: emptyList())

                // Создаём задачу
                val task = taskDataManager.createTask(
                    title = title,
                    description = "Импортировано из GitHub Issue #$issueNumber\n$owner/$repo",
                    priority = priority,
                    type = type,
                    reporterId = "github_import",
                    labels = listOf("github:$owner/$repo#$issueNumber", "imported")
                )

                importedTasks.add("#$issueNumber -> ${task.id}: $title")
            }

            ImportResult.Success(
                importedCount = importedTasks.size,
                skippedCount = skippedIssues.size,
                importedTasks = importedTasks,
                skippedIssues = skippedIssues
            )
        } catch (e: Exception) {
            ImportResult.Error("Ошибка импорта: ${e.message}")
        }
    }

    /**
     * Экспортировать задачу в GitHub Issue.
     * @param taskId ID задачи для экспорта
     * @param owner владелец репозитория
     * @param repo название репозитория
     */
    suspend fun exportTaskToIssue(
        taskId: String,
        owner: String,
        repo: String
    ): ExportResult {
        if (githubMcpClient == null) {
            return ExportResult.Error("GitHub MCP не подключен")
        }

        val task = taskDataManager.getTaskById(taskId)
            ?: return ExportResult.Error("Задача '$taskId' не найдена")

        // Проверяем не экспортирована ли уже
        val existingIssueLabel = task.labels.find { it.startsWith("github:$owner/$repo#") }
        if (existingIssueLabel != null) {
            return ExportResult.Error("Задача уже связана с $existingIssueLabel")
        }

        return try {
            // Формируем тело issue
            val body = buildString {
                appendLine(task.description)
                appendLine()
                appendLine("---")
                appendLine("**Imported from Team Assistant**")
                appendLine("- Task ID: ${task.id}")
                appendLine("- Priority: ${task.priority}")
                appendLine("- Type: ${task.type}")
                if (task.storyPoints != null) {
                    appendLine("- Story Points: ${task.storyPoints}")
                }
            }

            // Определяем labels для GitHub
            val githubLabels = mutableListOf<String>()
            when (task.priority) {
                TaskPriority.CRITICAL -> githubLabels.add("priority:critical")
                TaskPriority.HIGH -> githubLabels.add("priority:high")
                TaskPriority.MEDIUM -> {}
                TaskPriority.LOW -> githubLabels.add("priority:low")
            }
            when (task.type) {
                TaskType.BUG -> githubLabels.add("bug")
                TaskType.FEATURE -> githubLabels.add("enhancement")
                TaskType.TECH_DEBT -> githubLabels.add("tech-debt")
                TaskType.SPIKE -> githubLabels.add("research")
                TaskType.IMPROVEMENT -> githubLabels.add("improvement")
            }

            // Создаём issue через MCP
            val args = mutableMapOf<String, JsonElement>(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "title" to JsonPrimitive(task.title),
                "body" to JsonPrimitive(body)
            )
            if (githubLabels.isNotEmpty()) {
                args["labels"] = JsonArray(githubLabels.map { JsonPrimitive(it) })
            }

            val result = githubMcpClient.callTool("create_issue", args.mapValues {
                when (val v = it.value) {
                    is JsonPrimitive -> v
                    else -> JsonPrimitive(v.toString())
                }
            }.filterValues { it is JsonPrimitive } as Map<String, JsonPrimitive>)

            val responseText = result.content.firstOrNull()?.text ?: ""

            // Парсим номер созданного issue
            val issueNumberMatch = Regex("""#(\d+)""").find(responseText)
            val issueNumber = issueNumberMatch?.groupValues?.get(1)

            if (issueNumber != null) {
                // Обновляем задачу, добавляя ссылку на issue
                // Note: В текущей реализации нет метода для обновления labels,
                // поэтому просто возвращаем результат
                ExportResult.Success(
                    taskId = taskId,
                    issueNumber = issueNumber.toInt(),
                    issueUrl = "https://github.com/$owner/$repo/issues/$issueNumber"
                )
            } else {
                ExportResult.Success(
                    taskId = taskId,
                    issueNumber = 0,
                    issueUrl = "Issue создан (номер не получен)"
                )
            }
        } catch (e: Exception) {
            ExportResult.Error("Ошибка экспорта: ${e.message}")
        }
    }

    /**
     * Синхронизировать статус задачи с GitHub Issue.
     */
    suspend fun syncTaskStatus(
        taskId: String,
        owner: String,
        repo: String,
        issueNumber: Int
    ): SyncResult {
        if (githubMcpClient == null) {
            return SyncResult.Error("GitHub MCP не подключен")
        }

        val task = taskDataManager.getTaskById(taskId)
            ?: return SyncResult.Error("Задача '$taskId' не найдена")

        return try {
            // Определяем состояние для GitHub
            val githubState = when (task.status) {
                TaskStatus.DONE -> "closed"
                else -> "open"
            }

            // Обновляем issue через MCP
            val args = mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "issue_number" to JsonPrimitive(issueNumber),
                "state" to JsonPrimitive(githubState)
            )

            githubMcpClient.callTool("update_issue", args)

            SyncResult.Success(
                taskId = taskId,
                issueNumber = issueNumber,
                newState = githubState
            )
        } catch (e: Exception) {
            SyncResult.Error("Ошибка синхронизации: ${e.message}")
        }
    }

    private fun determinePriorityFromLabels(labels: List<String>): TaskPriority {
        return when {
            labels.any { it.contains("critical", ignoreCase = true) } -> TaskPriority.CRITICAL
            labels.any { it.contains("high", ignoreCase = true) || it.contains("urgent", ignoreCase = true) } -> TaskPriority.HIGH
            labels.any { it.contains("low", ignoreCase = true) } -> TaskPriority.LOW
            else -> TaskPriority.MEDIUM
        }
    }

    private fun determineTypeFromLabels(labels: List<String>): TaskType {
        return when {
            labels.any { it.contains("bug", ignoreCase = true) } -> TaskType.BUG
            labels.any { it.contains("enhancement", ignoreCase = true) || it.contains("feature", ignoreCase = true) } -> TaskType.FEATURE
            labels.any { it.contains("tech-debt", ignoreCase = true) || it.contains("refactor", ignoreCase = true) } -> TaskType.TECH_DEBT
            labels.any { it.contains("research", ignoreCase = true) || it.contains("spike", ignoreCase = true) } -> TaskType.SPIKE
            else -> TaskType.IMPROVEMENT
        }
    }
}

// ==================== Result classes ====================

sealed class ImportResult {
    data class Success(
        val importedCount: Int,
        val skippedCount: Int,
        val importedTasks: List<String>,
        val skippedIssues: List<String>
    ) : ImportResult()

    data class Error(val message: String) : ImportResult()
}

sealed class ExportResult {
    data class Success(
        val taskId: String,
        val issueNumber: Int,
        val issueUrl: String
    ) : ExportResult()

    data class Error(val message: String) : ExportResult()
}

sealed class SyncResult {
    data class Success(
        val taskId: String,
        val issueNumber: Int,
        val newState: String
    ) : SyncResult()

    data class Error(val message: String) : SyncResult()
}
