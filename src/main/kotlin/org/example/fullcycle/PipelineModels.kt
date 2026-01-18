package org.example.fullcycle

import kotlinx.serialization.Serializable

/**
 * Состояние пайплайна Full-Cycle
 */
sealed class PipelineState {
    data object Analyzing : PipelineState()
    data class PlanReady(val plan: ExecutionPlan) : PipelineState()
    data object AwaitingConfirmation : PipelineState()
    data object MakingChanges : PipelineState()
    data class CreatingBranch(val branchName: String) : PipelineState()
    data class Committing(val message: String) : PipelineState()
    data class Pushing(val branchName: String) : PipelineState()
    data class CreatingPR(val branch: String) : PipelineState()
    data class Reviewing(val iteration: Int, val maxIterations: Int = 10) : PipelineState()
    data class FixingReviewComments(val iteration: Int, val commentsCount: Int) : PipelineState()
    data class WaitingForCI(val prNumber: Int) : PipelineState()
    data class FixingCIError(val error: String, val attempt: Int) : PipelineState()
    data class ResolvingConflicts(val conflictFiles: List<String>) : PipelineState()
    data object Merging : PipelineState()
    data class Completed(val report: PipelineReport) : PipelineState()
    data class Failed(val reason: String, val recoverable: Boolean = false) : PipelineState()
    data class NeedsUserInput(val question: String, val options: List<String>) : PipelineState()
}

/**
 * План выполнения задачи
 */
@Serializable
data class ExecutionPlan(
    val taskDescription: String,
    val plannedChanges: List<PlannedChange>,
    val estimatedFilesCount: Int,
    val summary: String
)

/**
 * Запланированное изменение файла
 */
@Serializable
data class PlannedChange(
    val filePath: String,
    val changeType: ChangeType,
    val description: String
)

/**
 * Тип изменения
 */
@Serializable
enum class ChangeType {
    CREATE,
    MODIFY,
    DELETE
}

/**
 * Результат выполнения пайплайна
 */
@Serializable
data class PipelineReport(
    val success: Boolean,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val branchName: String? = null,
    val changedFiles: List<FileChange> = emptyList(),
    val reviewIterations: Int = 0,
    val ciRuns: Int = 0,
    val totalDuration: Long = 0,
    val summary: String = "",
    val errors: List<String> = emptyList()
)

/**
 * Информация об изменённом файле
 */
@Serializable
data class FileChange(
    val path: String,
    val linesAdded: Int,
    val linesRemoved: Int,
    val isNew: Boolean = false
)

/**
 * Результат self-review
 */
@Serializable
data class SelfReviewResult(
    val approved: Boolean,
    val comments: List<ReviewIssue>,
    val overallAssessment: String
)

/**
 * Проблема найденная при review
 */
@Serializable
data class ReviewIssue(
    val file: String,
    val line: Int? = null,
    val severity: IssueSeverity,
    val message: String,
    val suggestedFix: String? = null
)

/**
 * Серьёзность проблемы
 */
@Serializable
enum class IssueSeverity {
    CRITICAL,   // Блокирует merge
    WARNING,    // Желательно исправить
    SUGGESTION, // Можно улучшить
    NITPICK     // Мелочи
}

/**
 * Статус CI
 */
@Serializable
enum class CIStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

/**
 * Результат проверки CI
 */
@Serializable
data class CIResult(
    val status: CIStatus,
    val checkName: String? = null,
    val logs: String? = null,
    val errorMessage: String? = null
)

/**
 * Конфигурация пайплайна
 */
data class PipelineConfig(
    val maxReviewIterations: Int = 10,
    val maxCIRetries: Int = 5,
    val autoMerge: Boolean = true,
    val requireCIPass: Boolean = true,
    val protectedPatterns: List<String> = listOf(
        ".env",
        ".env.*",
        "**/secrets/**",
        "**/credentials/**",
        "**/*.pem",
        "**/*.key"
    )
)

/**
 * Защищённые файлы, которые нельзя редактировать
 */
object ProtectedFiles {
    private val patterns = listOf(
        Regex(".*\\.env$"),
        Regex(".*\\.env\\..*"),
        Regex(".*/secrets/.*"),
        Regex(".*/credentials/.*"),
        Regex(".*\\.pem$"),
        Regex(".*\\.key$")
    )

    fun isProtected(filePath: String): Boolean {
        return patterns.any { it.matches(filePath) }
    }
}
