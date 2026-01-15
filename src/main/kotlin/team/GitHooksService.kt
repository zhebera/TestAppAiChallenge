package org.example.team

import org.example.mcp.server.tasks.TaskDataManager
import org.example.mcp.server.tasks.TaskStatus

/**
 * Сервис интеграции с Git hooks.
 *
 * Функции:
 * - Автоматическое обновление статуса задачи при коммите с ID
 * - Обновление при мерже PR
 * - Парсинг task ID из commit message
 *
 * Формат упоминания задачи в коммите:
 * - [task_001] Fix memory leak
 * - fix: resolve issue [task_002]
 * - Closes task_003
 */
class GitHooksService(
    private val taskDataManager: TaskDataManager
) {
    companion object {
        // Паттерны для поиска task ID в commit message
        private val TASK_ID_PATTERNS = listOf(
            Regex("""\[task_(\w+)\]"""),           // [task_001]
            Regex("""task_(\w+)""", RegexOption.IGNORE_CASE),  // task_001
            Regex("""closes?\s+#?task_(\w+)""", RegexOption.IGNORE_CASE),  // closes task_001
            Regex("""fixes?\s+#?task_(\w+)""", RegexOption.IGNORE_CASE),   // fixes task_001
            Regex("""resolves?\s+#?task_(\w+)""", RegexOption.IGNORE_CASE) // resolves task_001
        )

        // Паттерны для определения действия
        private val CLOSE_PATTERNS = listOf(
            Regex("""closes?\s+""", RegexOption.IGNORE_CASE),
            Regex("""fixes?\s+""", RegexOption.IGNORE_CASE),
            Regex("""resolves?\s+""", RegexOption.IGNORE_CASE),
            Regex("""done\s*:""", RegexOption.IGNORE_CASE)
        )

        private val IN_PROGRESS_PATTERNS = listOf(
            Regex("""wip\s*:""", RegexOption.IGNORE_CASE),
            Regex("""work\s+in\s+progress""", RegexOption.IGNORE_CASE),
            Regex("""started?\s+""", RegexOption.IGNORE_CASE)
        )

        private val REVIEW_PATTERNS = listOf(
            Regex("""review\s*:""", RegexOption.IGNORE_CASE),
            Regex("""ready\s+for\s+review""", RegexOption.IGNORE_CASE),
            Regex("""pr\s*:""", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Обработать commit message и обновить связанные задачи.
     *
     * @param commitMessage текст коммита
     * @param authorId ID автора коммита (опционально)
     * @return список обновлённых задач
     */
    fun processCommit(commitMessage: String, authorId: String? = null): CommitProcessResult {
        val taskIds = extractTaskIds(commitMessage)

        if (taskIds.isEmpty()) {
            return CommitProcessResult(
                found = false,
                message = "Задачи не найдены в коммите"
            )
        }

        val results = mutableListOf<TaskUpdateInfo>()
        val newStatus = determineStatusFromCommit(commitMessage)

        for (taskId in taskIds) {
            val fullTaskId = "task_$taskId"
            val task = taskDataManager.getTaskById(fullTaskId)

            if (task == null) {
                results.add(TaskUpdateInfo(
                    taskId = fullTaskId,
                    success = false,
                    message = "Задача не найдена"
                ))
                continue
            }

            // Обновляем статус если определён и отличается
            if (newStatus != null && task.status != newStatus) {
                val updated = taskDataManager.updateTaskStatus(fullTaskId, newStatus)
                if (updated != null) {
                    // Добавляем комментарий о коммите
                    taskDataManager.addTaskComment(
                        taskId = fullTaskId,
                        authorId = authorId ?: "git_hook",
                        content = "Статус обновлён по коммиту: ${commitMessage.lines().first()}"
                    )

                    results.add(TaskUpdateInfo(
                        taskId = fullTaskId,
                        success = true,
                        message = "Статус изменён: ${task.status} -> $newStatus",
                        oldStatus = task.status,
                        newStatus = newStatus
                    ))
                } else {
                    results.add(TaskUpdateInfo(
                        taskId = fullTaskId,
                        success = false,
                        message = "Ошибка обновления статуса"
                    ))
                }
            } else {
                // Просто добавляем комментарий о коммите
                taskDataManager.addTaskComment(
                    taskId = fullTaskId,
                    authorId = authorId ?: "git_hook",
                    content = "Коммит: ${commitMessage.lines().first()}"
                )

                results.add(TaskUpdateInfo(
                    taskId = fullTaskId,
                    success = true,
                    message = "Комментарий добавлен (статус не изменён)"
                ))
            }
        }

        return CommitProcessResult(
            found = true,
            message = "Обработано ${results.size} задач",
            updates = results
        )
    }

    /**
     * Обработать событие мержа PR.
     *
     * @param prTitle заголовок PR
     * @param prBody тело PR
     * @param merged был ли PR смержен
     */
    fun processPullRequest(prTitle: String, prBody: String?, merged: Boolean): CommitProcessResult {
        val fullText = "$prTitle\n${prBody ?: ""}"
        val taskIds = extractTaskIds(fullText)

        if (taskIds.isEmpty()) {
            return CommitProcessResult(
                found = false,
                message = "Задачи не найдены в PR"
            )
        }

        val results = mutableListOf<TaskUpdateInfo>()
        val newStatus = if (merged) TaskStatus.DONE else TaskStatus.REVIEW

        for (taskId in taskIds) {
            val fullTaskId = "task_$taskId"
            val task = taskDataManager.getTaskById(fullTaskId)

            if (task == null) {
                results.add(TaskUpdateInfo(
                    taskId = fullTaskId,
                    success = false,
                    message = "Задача не найдена"
                ))
                continue
            }

            // При мерже - закрываем задачу, иначе - переводим в review
            if (task.status != newStatus) {
                val updated = taskDataManager.updateTaskStatus(fullTaskId, newStatus)
                if (updated != null) {
                    val action = if (merged) "PR смержен" else "PR открыт на ревью"
                    taskDataManager.addTaskComment(
                        taskId = fullTaskId,
                        authorId = "git_hook",
                        content = "$action: $prTitle"
                    )

                    results.add(TaskUpdateInfo(
                        taskId = fullTaskId,
                        success = true,
                        message = "$action, статус: ${task.status} -> $newStatus",
                        oldStatus = task.status,
                        newStatus = newStatus
                    ))
                }
            }
        }

        return CommitProcessResult(
            found = true,
            message = "Обработано ${results.size} задач",
            updates = results
        )
    }

    /**
     * Извлекает ID задач из текста.
     */
    fun extractTaskIds(text: String): List<String> {
        val taskIds = mutableSetOf<String>()

        for (pattern in TASK_ID_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                taskIds.add(match.groupValues[1])
            }
        }

        return taskIds.toList()
    }

    /**
     * Определяет новый статус задачи на основе commit message.
     */
    private fun determineStatusFromCommit(message: String): TaskStatus? {
        // Проверяем паттерны закрытия
        for (pattern in CLOSE_PATTERNS) {
            if (pattern.containsMatchIn(message)) {
                return TaskStatus.DONE
            }
        }

        // Проверяем паттерны review
        for (pattern in REVIEW_PATTERNS) {
            if (pattern.containsMatchIn(message)) {
                return TaskStatus.REVIEW
            }
        }

        // Проверяем паттерны in_progress
        for (pattern in IN_PROGRESS_PATTERNS) {
            if (pattern.containsMatchIn(message)) {
                return TaskStatus.IN_PROGRESS
            }
        }

        // По умолчанию не меняем статус
        return null
    }

    /**
     * Генерирует пример git hook скрипта.
     */
    fun generatePostCommitHook(projectPath: String): String {
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("# Post-commit hook для интеграции с Team Assistant")
            appendLine("# Установите в .git/hooks/post-commit")
            appendLine()
            appendLine("COMMIT_MSG=\$(git log -1 --pretty=%B)")
            appendLine("PROJECT_PATH=\"$projectPath\"")
            appendLine()
            appendLine("echo \"Team Assistant: Проверка commit message на упоминание задач...\"")
            appendLine()
            appendLine("# Простая проверка наличия task_XXX в сообщении")
            appendLine("if echo \"\$COMMIT_MSG\" | grep -qE \"task_[0-9a-zA-Z]+\"; then")
            appendLine("    echo \"Team Assistant: Найдена ссылка на задачу в коммите\"")
            appendLine("fi")
        }
    }

    /**
     * Генерирует prepare-commit-msg hook для подсказки формата.
     */
    fun generatePrepareCommitMsgHook(): String {
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("# Prepare-commit-msg hook для Team Assistant")
            appendLine("# Добавляет подсказку о формате упоминания задач")
            appendLine()
            appendLine("COMMIT_MSG_FILE=\$1")
            appendLine("COMMIT_SOURCE=\$2")
            appendLine()
            appendLine("# Если это не amend и не merge")
            appendLine("if [ -z \"\$COMMIT_SOURCE\" ]; then")
            appendLine("    echo \"\" >> \"\$COMMIT_MSG_FILE\"")
            appendLine("    echo \"# Team Assistant: Упомяните задачу в формате [task_XXX]\" >> \"\$COMMIT_MSG_FILE\"")
            appendLine("    echo \"# Примеры:\" >> \"\$COMMIT_MSG_FILE\"")
            appendLine("    echo \"#   [task_001] Fix memory leak\" >> \"\$COMMIT_MSG_FILE\"")
            appendLine("    echo \"#   Closes task_002\" >> \"\$COMMIT_MSG_FILE\"")
            appendLine("fi")
        }
    }
}

// ==================== Result classes ====================

data class CommitProcessResult(
    val found: Boolean,
    val message: String,
    val updates: List<TaskUpdateInfo> = emptyList()
)

data class TaskUpdateInfo(
    val taskId: String,
    val success: Boolean,
    val message: String,
    val oldStatus: TaskStatus? = null,
    val newStatus: TaskStatus? = null
)
