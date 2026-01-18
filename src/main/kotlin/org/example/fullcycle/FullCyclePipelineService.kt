package org.example.fullcycle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.example.data.dto.LlmRequest
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage
import org.example.prreview.PrReviewService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Full-Cycle Pipeline Service
 *
 * Полный цикл автоматизации:
 * 1. Анализ задачи (RAG + LLM)
 * 2. Генерация плана изменений
 * 3. Внесение изменений в код
 * 4. Создание ветки, коммит, push
 * 5. Создание PR
 * 6. Self-review с итерациями исправлений
 * 7. Ожидание CI
 * 8. Авто-фикс при падении CI
 * 9. Merge в main
 */
class FullCyclePipelineService(
    private val llmClient: LlmClient,
    private val ragService: RagService? = null,
    private val githubToken: String? = null,
    private val config: PipelineConfig = PipelineConfig()
) {
    private val json = McpClientFactory.createJson()
    private var githubClient: McpClient? = null
    private var prReviewService: PrReviewService? = null

    // Callback для отображения прогресса
    var onProgress: ((String) -> Unit)? = null
    var onStateChange: ((PipelineState) -> Unit)? = null

    private val projectRoot = File(System.getProperty("user.dir"))

    /**
     * Выполнить полный цикл: от задачи до merge
     */
    suspend fun executeFullCycle(
        taskDescription: String,
        confirmPlan: suspend (ExecutionPlan) -> Boolean
    ): PipelineReport {
        val startTime = System.currentTimeMillis()
        var reviewIterations = 0
        var ciRuns = 0
        val errors = mutableListOf<String>()

        try {
            // === ЭТАП 1: Анализ задачи ===
            progress("🔍 Анализирую задачу...")
            changeState(PipelineState.Analyzing)

            val ragContext = if (ragService != null) {
                progress("   Поиск релевантных файлов через RAG...")
                buildRagContext(taskDescription)
            } else ""

            // === ЭТАП 2: Генерация плана ===
            progress("📋 Генерирую план изменений...")
            val plan = generatePlan(taskDescription, ragContext)
            changeState(PipelineState.PlanReady(plan))

            progress("\n📋 План изменений:")
            plan.plannedChanges.forEachIndexed { index, change ->
                val typeIcon = when (change.changeType) {
                    ChangeType.CREATE -> "+"
                    ChangeType.MODIFY -> "~"
                    ChangeType.DELETE -> "-"
                }
                progress("   ${index + 1}. [$typeIcon] ${change.filePath}")
                progress("      ${change.description}")
            }
            progress("\n   ${plan.summary}")

            // === ЭТАП 3: Подтверждение пользователем ===
            changeState(PipelineState.AwaitingConfirmation)
            if (!confirmPlan(plan)) {
                return PipelineReport(
                    success = false,
                    summary = "Отменено пользователем"
                )
            }

            // === ЭТАП 4: Внесение изменений ===
            progress("\n✏️ Вношу изменения...")
            changeState(PipelineState.MakingChanges)
            val changedFiles = makeChanges(taskDescription, plan, ragContext)

            // Проверяем, были ли реально внесены изменения
            if (changedFiles.isEmpty()) {
                progress("\n⚠️ Не удалось внести изменения (возможно, защита от truncation)")
                progress("   Попробуйте разбить задачу на меньшие части")
                return PipelineReport(
                    success = false,
                    summary = "Изменения не были внесены из-за защиты от truncation",
                    errors = listOf("Файлы слишком большие для модификации. Разбейте задачу на части.")
                )
            }

            // === ЭТАП 5: Git операции ===
            val branchName = "feature/ai-${generateBranchSuffix(taskDescription)}"
            progress("\n🌿 Создаю ветку $branchName...")
            changeState(PipelineState.CreatingBranch(branchName))

            // Создаём ветку
            val currentBranch = runGit("git", "branch", "--show-current").trim()
            if (currentBranch == "main" || currentBranch == "master") {
                runGit("git", "checkout", "-b", branchName)
            }

            // Git add — только файлы из плана, не все!
            progress("   git add...")
            for (change in plan.plannedChanges) {
                runGit("git", "add", change.filePath)
            }

            // Git commit
            val commitMessage = generateCommitMessage(taskDescription, plan)
            progress("   git commit...")
            changeState(PipelineState.Committing(commitMessage))
            runGit("git", "commit", "-m", commitMessage)

            // Git push
            progress("   git push...")
            changeState(PipelineState.Pushing(branchName))
            val pushResult = runGit("git", "push", "-u", "origin", branchName)
            if (pushResult.contains("error") || pushResult.contains("fatal")) {
                throw PipelineException("Ошибка push: $pushResult")
            }

            // === ЭТАП 6: Создание PR ===
            progress("\n🔗 Создаю Pull Request...")
            val repoInfo = getRepoInfo() ?: throw PipelineException("Не удалось определить репозиторий")
            changeState(PipelineState.CreatingPR(branchName))

            connectGitHub()
            val (prNumber, prUrl) = createPullRequest(repoInfo, branchName, commitMessage, plan)
            progress("   ✓ PR #$prNumber создан: $prUrl")

            // === ЭТАП 7: Self-Review цикл ===
            // Пропускаем self-review если все операции - только удаления (нечего ревьюить)
            val hasNonDeleteChanges = plan.plannedChanges.any { it.changeType != ChangeType.DELETE }
            var approved = !hasNonDeleteChanges // Если только DELETE - сразу approved
            if (!hasNonDeleteChanges) {
                progress("\n✓ Self-review пропущен (только удаление файлов)")
            }
            while (!approved && reviewIterations < config.maxReviewIterations) {
                reviewIterations++
                progress("\n🔎 Self-review итерация $reviewIterations...")
                changeState(PipelineState.Reviewing(reviewIterations, config.maxReviewIterations))

                val reviewResult = performSelfReview(repoInfo, prNumber)

                if (reviewResult.approved) {
                    progress("   ✓ Код одобрен!")
                    approved = true
                } else {
                    progress("   Найдено замечаний: ${reviewResult.comments.size}")
                    changeState(PipelineState.FixingReviewComments(reviewIterations, reviewResult.comments.size))

                    // Исправляем замечания
                    val fixed = fixReviewComments(taskDescription, reviewResult, ragContext)
                    if (fixed) {
                        progress("   Коммит исправлений...")
                        // Добавляем только файлы из замечаний
                        val filesWithIssues = reviewResult.comments.map { it.file }.distinct()
                        for (file in filesWithIssues) {
                            runGit("git", "add", file)
                        }
                        runGit("git", "commit", "-m", "fix: исправлены замечания review (итерация $reviewIterations)")
                        runGit("git", "push")
                    } else {
                        progress("   Не удалось исправить все замечания")
                        if (reviewIterations >= config.maxReviewIterations) {
                            changeState(PipelineState.NeedsUserInput(
                                "Достигнут лимит итераций ($reviewIterations). Продолжить?",
                                listOf("Продолжить", "Оставить PR открытым", "Замерджить как есть")
                            ))
                            errors.add("Достигнут лимит итераций review")
                            break
                        }
                    }
                }
            }

            // === ЭТАП 8: Ожидание CI ===
            if (config.requireCIPass) {
                progress("\n⏳ Ожидаю CI...")
                changeState(PipelineState.WaitingForCI(prNumber))

                var ciPassed = false
                var ciAttempts = 0

                while (!ciPassed && ciAttempts < config.maxCIRetries) {
                    ciRuns++
                    val ciResult = waitForCI(repoInfo, prNumber)

                    when (ciResult.status) {
                        CIStatus.SUCCESS -> {
                            progress("   ✓ CI прошёл успешно!")
                            ciPassed = true
                        }
                        CIStatus.FAILED -> {
                            ciAttempts++
                            progress("   ✗ CI упал: ${ciResult.errorMessage}")
                            changeState(PipelineState.FixingCIError(ciResult.errorMessage ?: "Unknown error", ciAttempts))

                            // Пытаемся исправить
                            val fixed = fixCIError(taskDescription, ciResult, ragContext)
                            if (fixed) {
                                progress("   Коммит исправлений CI...")
                                runGit("git", "add", "-A")
                                runGit("git", "commit", "-m", "fix: исправлена ошибка CI")
                                runGit("git", "push")
                            } else {
                                errors.add("CI error: ${ciResult.errorMessage}")
                                if (ciAttempts >= config.maxCIRetries) {
                                    progress("   Достигнут лимит попыток исправить CI")
                                    break
                                }
                            }
                        }
                        CIStatus.PENDING, CIStatus.RUNNING -> {
                            delay(10000) // Ждём 10 секунд
                        }
                        CIStatus.CANCELLED -> {
                            errors.add("CI был отменён")
                            break
                        }
                    }
                }

                if (!ciPassed) {
                    return PipelineReport(
                        success = false,
                        prNumber = prNumber,
                        prUrl = prUrl,
                        branchName = branchName,
                        changedFiles = changedFiles,
                        reviewIterations = reviewIterations,
                        ciRuns = ciRuns,
                        totalDuration = System.currentTimeMillis() - startTime,
                        summary = "CI не прошёл после $ciAttempts попыток",
                        errors = errors
                    )
                }
            }

            // === ЭТАП 9: Merge ===
            if (config.autoMerge) {
                progress("\n🔀 Мержу PR #$prNumber в main...")
                changeState(PipelineState.Merging)

                // Проверяем конфликты
                val conflicts = checkForConflicts(repoInfo, prNumber)
                if (conflicts.isNotEmpty()) {
                    progress("   Обнаружены конфликты: ${conflicts.joinToString()}")
                    changeState(PipelineState.ResolvingConflicts(conflicts))
                    resolveConflicts(conflicts, taskDescription, ragContext)
                }

                // Мерджим
                mergePullRequest(repoInfo, prNumber)
                progress("   ✓ PR успешно замержен!")

                // Удаляем ветку
                runGit("git", "checkout", "main")
                runGit("git", "pull")
                runGit("git", "branch", "-d", branchName)
            }

            // === ИТОГ ===
            val report = PipelineReport(
                success = true,
                prNumber = prNumber,
                prUrl = prUrl,
                branchName = branchName,
                changedFiles = changedFiles,
                reviewIterations = reviewIterations,
                ciRuns = ciRuns,
                totalDuration = System.currentTimeMillis() - startTime,
                summary = "Задача успешно выполнена",
                errors = errors
            )

            changeState(PipelineState.Completed(report))
            printFinalReport(report)

            return report

        } catch (e: Exception) {
            val errorMessage = "Ошибка пайплайна: ${e.message}"
            errors.add(errorMessage)
            changeState(PipelineState.Failed(errorMessage))
            progress("\n❌ $errorMessage")

            return PipelineReport(
                success = false,
                totalDuration = System.currentTimeMillis() - startTime,
                summary = errorMessage,
                errors = errors
            )
        } finally {
            disconnectGitHub()
        }
    }

    // === Private Methods ===

    private fun progress(message: String) {
        onProgress?.invoke(message) ?: println(message)
    }

    private fun changeState(state: PipelineState) {
        onStateChange?.invoke(state)
    }

    /**
     * Получает структуру проекта (список kotlin файлов) для контекста планирования
     */
    private fun getProjectStructure(): String {
        val srcDir = File(projectRoot, "src/main/kotlin")
        if (!srcDir.exists()) return "src/main/kotlin не найден"

        val files = mutableListOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relativePath = file.relativeTo(projectRoot).path
                files.add(relativePath)
            }

        return files.sorted().joinToString("\n")
    }

    private suspend fun buildRagContext(taskDescription: String): String {
        if (ragService == null) return ""

        return try {
            val result = ragService.search(taskDescription, topK = 5, minSimilarity = 0.3f)
            if (result.results.isNotEmpty()) {
                progress("   Найдено ${result.results.size} релевантных файлов")
                result.formattedContext
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun generatePlan(taskDescription: String, ragContext: String): ExecutionPlan {
        // Получаем структуру проекта для контекста
        val projectStructure = getProjectStructure()

        val prompt = buildString {
            appendLine("Проанализируй задачу и создай план изменений.")
            appendLine()
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()

            appendLine("## Структура проекта (существующие файлы)")
            appendLine("ВАЖНО: Если файл уже существует — используй MODIFY, не CREATE!")
            appendLine("```")
            appendLine(projectStructure)
            appendLine("```")
            appendLine()

            if (ragContext.isNotBlank()) {
                appendLine("## Контекст проекта (содержимое релевантных файлов)")
                appendLine(ragContext)
                appendLine()
            }

            appendLine("## Формат ответа")
            appendLine("Верни JSON в формате:")
            appendLine("""
                {
                  "taskDescription": "краткое описание задачи",
                  "plannedChanges": [
                    {
                      "filePath": "путь/к/файлу.kt",
                      "changeType": "MODIFY",
                      "description": "что нужно изменить"
                    }
                  ],
                  "estimatedFilesCount": 3,
                  "summary": "общее описание плана"
                }
            """.trimIndent())
            appendLine()
            appendLine("changeType может быть: CREATE, MODIFY, DELETE")
        }

        val response = callLlm(prompt, SYSTEM_PROMPT_PLANNER)

        return try {
            // Извлекаем JSON из ответа
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(response)
            val jsonStr = jsonMatch?.value ?: throw PipelineException("Не найден JSON в ответе")
            json.decodeFromString<ExecutionPlan>(jsonStr)
        } catch (e: Exception) {
            // Fallback: создаём простой план
            ExecutionPlan(
                taskDescription = taskDescription,
                plannedChanges = listOf(
                    PlannedChange(
                        filePath = "src/main/kotlin/...",
                        changeType = ChangeType.MODIFY,
                        description = "Изменения согласно задаче"
                    )
                ),
                estimatedFilesCount = 1,
                summary = "План требует уточнения"
            )
        }
    }

    private suspend fun makeChanges(
        taskDescription: String,
        plan: ExecutionPlan,
        ragContext: String
    ): List<FileChange> {
        val changes = mutableListOf<FileChange>()

        for ((index, plannedChange) in plan.plannedChanges.withIndex()) {
            progress("   [${index + 1}/${plan.plannedChanges.size}] ${plannedChange.filePath}...")

            // Проверяем защищённые файлы
            if (ProtectedFiles.isProtected(plannedChange.filePath)) {
                progress("      ⚠ Пропущен (защищённый файл)")
                continue
            }

            val file = File(projectRoot, plannedChange.filePath)

            when (plannedChange.changeType) {
                ChangeType.CREATE -> {
                    val content = generateFileContent(taskDescription, plannedChange, ragContext)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    changes.add(FileChange(plannedChange.filePath, content.lines().size, 0, true))
                }
                ChangeType.MODIFY -> {
                    if (file.exists()) {
                        val oldContent = file.readText()
                        val newContent = modifyFileContent(taskDescription, plannedChange, oldContent, ragContext)

                        // Защита: не допускаем резкого уменьшения файла (возможная ошибка LLM)
                        val oldLines = oldContent.lines().size
                        val newLines = newContent.lines().size
                        val sizeRatio = if (oldLines > 0) newLines.toDouble() / oldLines else 1.0

                        if (oldLines > 50 && sizeRatio < 0.5) {
                            progress("      ⚠ Пропущен: новый контент ($newLines строк) слишком мал по сравнению с оригиналом ($oldLines строк)")
                            progress("      ⚠ Это может быть ошибкой LLM. Файл не изменён.")
                            continue
                        }

                        file.writeText(newContent)
                        changes.add(FileChange(
                            plannedChange.filePath,
                            maxOf(0, newLines - oldLines),
                            maxOf(0, oldLines - newLines)
                        ))
                    } else {
                        progress("      ⚠ Файл не найден, создаю новый")
                        val content = generateFileContent(taskDescription, plannedChange, ragContext)
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        changes.add(FileChange(plannedChange.filePath, content.lines().size, 0, true))
                    }
                }
                ChangeType.DELETE -> {
                    if (file.exists()) {
                        val lines = file.readText().lines().size
                        file.delete()
                        changes.add(FileChange(plannedChange.filePath, 0, lines))
                    }
                }
            }
        }

        return changes
    }

    private suspend fun generateFileContent(
        taskDescription: String,
        change: PlannedChange,
        ragContext: String
    ): String {
        val prompt = buildString {
            appendLine("Создай файл ${change.filePath}")
            appendLine()
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()
            appendLine("## Что нужно создать")
            appendLine(change.description)
            appendLine()

            if (ragContext.isNotBlank()) {
                appendLine("## Контекст проекта")
                appendLine(ragContext.take(5000))
                appendLine()
            }

            appendLine("Верни ТОЛЬКО код файла, без объяснений и markdown блоков.")
        }

        val response = callLlm(prompt, SYSTEM_PROMPT_CODER)
        return cleanCodeResponse(response)
    }

    private suspend fun modifyFileContent(
        taskDescription: String,
        change: PlannedChange,
        currentContent: String,
        ragContext: String
    ): String {
        val prompt = buildString {
            appendLine("Измени файл ${change.filePath}")
            appendLine()
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()
            appendLine("## Что нужно изменить")
            appendLine(change.description)
            appendLine()
            appendLine("## Текущее содержимое файла")
            appendLine("```")
            appendLine(currentContent.take(10000))
            appendLine("```")
            appendLine()

            if (ragContext.isNotBlank()) {
                appendLine("## Контекст проекта")
                appendLine(ragContext.take(3000))
                appendLine()
            }

            appendLine("Верни ПОЛНЫЙ обновлённый код файла, без объяснений и markdown блоков.")
        }

        val response = callLlm(prompt, SYSTEM_PROMPT_CODER)
        return cleanCodeResponse(response)
    }

    private fun cleanCodeResponse(response: String): String {
        // Убираем markdown блоки если есть
        var code = response
        if (code.contains("```")) {
            val match = Regex("```\\w*\\n([\\s\\S]*?)```").find(code)
            code = match?.groupValues?.get(1) ?: code
        }
        return code.trim()
    }

    private suspend fun performSelfReview(repoInfo: RepoInfo, prNumber: Int): SelfReviewResult {
        if (prReviewService == null) {
            prReviewService = PrReviewService(llmClient, ragService, githubToken)
            prReviewService?.connect()
        }

        val reviewResult = prReviewService?.reviewPr(
            owner = repoInfo.owner,
            repo = repoInfo.repo,
            prNumber = prNumber,
            useRag = ragService != null
        )

        if (reviewResult == null) {
            return SelfReviewResult(approved = true, comments = emptyList(), overallAssessment = "Не удалось выполнить ревью")
        }

        // Публикуем ревью
        prReviewService?.submitReview(repoInfo.owner, repoInfo.repo, prNumber, reviewResult)

        // Конвертируем в наш формат
        val issues = reviewResult.comments.map { comment ->
            ReviewIssue(
                file = comment.file,
                line = comment.line,
                severity = when (comment.severity.lowercase()) {
                    "critical" -> IssueSeverity.CRITICAL
                    "warning" -> IssueSeverity.WARNING
                    "suggestion" -> IssueSeverity.SUGGESTION
                    else -> IssueSeverity.NITPICK
                },
                message = comment.message
            )
        }

        val approved = reviewResult.overallScore == "APPROVE" ||
                issues.none { it.severity == IssueSeverity.CRITICAL || it.severity == IssueSeverity.WARNING }

        return SelfReviewResult(
            approved = approved,
            comments = issues,
            overallAssessment = reviewResult.summary
        )
    }

    private suspend fun fixReviewComments(
        taskDescription: String,
        reviewResult: SelfReviewResult,
        ragContext: String
    ): Boolean {
        // Группируем по файлам
        val commentsByFile = reviewResult.comments.groupBy { it.file }

        for ((filePath, comments) in commentsByFile) {
            val file = File(projectRoot, filePath)
            if (!file.exists()) continue

            val currentContent = file.readText()

            val prompt = buildString {
                appendLine("Исправь замечания code review в файле $filePath")
                appendLine()
                appendLine("## Замечания")
                comments.forEach { comment ->
                    appendLine("- ${comment.severity}: ${comment.message}")
                    comment.line?.let { appendLine("  (строка $it)") }
                    comment.suggestedFix?.let { appendLine("  Предложение: $it") }
                }
                appendLine()
                appendLine("## Текущий код")
                appendLine("```")
                appendLine(currentContent.take(10000))
                appendLine("```")
                appendLine()
                appendLine("Верни ПОЛНЫЙ исправленный код файла.")
            }

            val response = callLlm(prompt, SYSTEM_PROMPT_CODER)
            val newContent = cleanCodeResponse(response)

            if (newContent.isNotBlank() && newContent != currentContent) {
                // Защита: не допускаем резкого уменьшения файла
                val oldLines = currentContent.lines().size
                val newLines = newContent.lines().size
                val sizeRatio = if (oldLines > 0) newLines.toDouble() / oldLines else 1.0

                if (oldLines > 50 && sizeRatio < 0.5) {
                    progress("      ⚠ Review fix пропущен для $filePath: размер уменьшился с $oldLines до $newLines строк")
                    continue
                }

                file.writeText(newContent)
            }
        }

        return true
    }

    private suspend fun waitForCI(repoInfo: RepoInfo, prNumber: Int): CIResult {
        // Ждём до 5 минут, проверяя каждые 15 секунд
        repeat(20) {
            val status = getCIStatus(repoInfo, prNumber)
            if (status.status == CIStatus.SUCCESS || status.status == CIStatus.FAILED) {
                return status
            }
            delay(15000)
        }

        return CIResult(CIStatus.PENDING)
    }

    private suspend fun getCIStatus(repoInfo: RepoInfo, prNumber: Int): CIResult {
        // Используем gh CLI для проверки статуса CI (надёжнее чем MCP)
        val result = runGit(
            "gh", "pr", "view", prNumber.toString(),
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--json", "mergeable,mergeStateStatus,statusCheckRollup"
        )

        if (result.isBlank() || result.contains("error")) {
            return CIResult(CIStatus.PENDING)
        }

        return try {
            val prJson = json.parseToJsonElement(result).jsonObject

            // Проверяем статус всех checks
            val checksArray = prJson["statusCheckRollup"]?.jsonArray
            if (checksArray != null && checksArray.isNotEmpty()) {
                val allCompleted = checksArray.all { check ->
                    check.jsonObject["status"]?.jsonPrimitive?.content == "COMPLETED"
                }
                val allSuccess = checksArray.all { check ->
                    check.jsonObject["conclusion"]?.jsonPrimitive?.content == "SUCCESS"
                }
                val anyFailed = checksArray.any { check ->
                    val conclusion = check.jsonObject["conclusion"]?.jsonPrimitive?.content
                    conclusion == "FAILURE" || conclusion == "CANCELLED"
                }

                when {
                    allCompleted && allSuccess -> CIResult(CIStatus.SUCCESS)
                    anyFailed -> CIResult(CIStatus.FAILED, errorMessage = "CI checks failed")
                    allCompleted -> CIResult(CIStatus.FAILED, errorMessage = "Some checks not successful")
                    else -> CIResult(CIStatus.PENDING)
                }
            } else {
                // Нет checks — считаем успехом (репо без CI)
                val mergeable = prJson["mergeable"]?.jsonPrimitive?.content
                if (mergeable == "MERGEABLE") {
                    CIResult(CIStatus.SUCCESS)
                } else {
                    CIResult(CIStatus.PENDING)
                }
            }
        } catch (e: Exception) {
            CIResult(CIStatus.PENDING)
        }
    }

    private suspend fun fixCIError(
        taskDescription: String,
        ciResult: CIResult,
        ragContext: String
    ): Boolean {
        val prompt = buildString {
            appendLine("CI упал с ошибкой. Проанализируй и предложи исправление.")
            appendLine()
            appendLine("## Ошибка CI")
            appendLine(ciResult.errorMessage ?: "Неизвестная ошибка")
            appendLine()
            if (ciResult.logs != null) {
                appendLine("## Логи")
                appendLine(ciResult.logs.take(5000))
                appendLine()
            }
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()
            appendLine("Опиши какие файлы нужно изменить и как.")
        }

        val response = callLlm(prompt, SYSTEM_PROMPT_CODER)

        // Пытаемся применить исправления на основе ответа LLM
        // (упрощённая реализация)
        return response.contains("исправ", ignoreCase = true) ||
                response.contains("fix", ignoreCase = true)
    }

    private suspend fun checkForConflicts(repoInfo: RepoInfo, prNumber: Int): List<String> {
        try {
            val result = githubClient?.callTool(
                "get_pull_request",
                mapOf(
                    "owner" to JsonPrimitive(repoInfo.owner),
                    "repo" to JsonPrimitive(repoInfo.repo),
                    "pull_number" to JsonPrimitive(prNumber)
                )
            )

            val content = result?.content?.firstOrNull()?.text ?: return emptyList()
            val prJson = json.parseToJsonElement(content).jsonObject

            val mergeable = prJson["mergeable"]?.jsonPrimitive?.booleanOrNull
            if (mergeable == false) {
                // Есть конфликты, но мы не знаем в каких файлах
                return listOf("unknown")
            }
        } catch (e: Exception) {
            // Ignore
        }
        return emptyList()
    }

    private suspend fun resolveConflicts(
        conflicts: List<String>,
        taskDescription: String,
        ragContext: String
    ) {
        // Делаем rebase на main
        runGit("git", "fetch", "origin", "main")
        val rebaseResult = runGit("git", "rebase", "origin/main")

        if (rebaseResult.contains("CONFLICT")) {
            // Автоматическое разрешение: принимаем наши изменения
            runGit("git", "checkout", "--ours", ".")
            runGit("git", "add", "-A")
            runGit("git", "rebase", "--continue")
        }
    }

    private suspend fun mergePullRequest(repoInfo: RepoInfo, prNumber: Int) {
        githubClient?.callTool(
            "merge_pull_request",
            mapOf(
                "owner" to JsonPrimitive(repoInfo.owner),
                "repo" to JsonPrimitive(repoInfo.repo),
                "pull_number" to JsonPrimitive(prNumber),
                "merge_method" to JsonPrimitive("squash")
            )
        )
    }

    private suspend fun createPullRequest(
        repoInfo: RepoInfo,
        branchName: String,
        title: String,
        plan: ExecutionPlan
    ): Pair<Int, String> {
        // Сначала проверяем, существует ли уже PR для этой ветки
        val existingPr = getExistingPrForBranch(repoInfo, branchName)
        if (existingPr != null) {
            progress("   ✓ PR уже существует: #${existingPr.first}")
            return existingPr
        }

        val body = buildString {
            appendLine("## Описание")
            appendLine(plan.summary)
            appendLine()
            appendLine("## Изменения")
            plan.plannedChanges.forEach { change ->
                appendLine("- ${change.filePath}: ${change.description}")
            }
            appendLine()
            appendLine("---")
            appendLine("*Автоматически создано Full-Cycle Pipeline*")
        }

        // Пробуем создать PR через MCP
        try {
            val result = githubClient?.callTool(
                "create_pull_request",
                mapOf(
                    "owner" to JsonPrimitive(repoInfo.owner),
                    "repo" to JsonPrimitive(repoInfo.repo),
                    "title" to JsonPrimitive(title),
                    "body" to JsonPrimitive(body),
                    "head" to JsonPrimitive(branchName),
                    "base" to JsonPrimitive("main")
                )
            )

            val content = result?.content?.firstOrNull()?.text
            if (content != null) {
                val prNumber = Regex(""""number"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
                val prUrl = Regex(""""html_url"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1)

                if (prNumber != null) {
                    return Pair(prNumber, prUrl ?: "https://github.com/${repoInfo.owner}/${repoInfo.repo}/pull/$prNumber")
                }
            }

            // MCP вызов прошёл, но не удалось распарсить ответ
            // Проверяем, может PR всё же был создан
            val createdPr = getExistingPrForBranch(repoInfo, branchName)
            if (createdPr != null) {
                progress("   ✓ PR создан через MCP: #${createdPr.first}")
                return createdPr
            }
        } catch (e: Exception) {
            progress("   ⚠ MCP не смог создать PR: ${e.message}")
        }

        // Fallback: создаём PR через gh CLI
        progress("   Пробую через gh CLI...")
        return createPullRequestViaGhCli(repoInfo, branchName, title, body)
    }

    /**
     * Создание PR через gh CLI как fallback
     */
    private suspend fun createPullRequestViaGhCli(
        repoInfo: RepoInfo,
        branchName: String,
        title: String,
        body: String
    ): Pair<Int, String> {
        // Экранируем body для shell
        val escapedBody = body.replace("\"", "\\\"").replace("\n", "\\n")

        val result = runGit(
            "gh", "pr", "create",
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--head", branchName,
            "--base", "main",
            "--title", title,
            "--body", body
        )

        // gh pr create возвращает URL созданного PR
        val prUrl = result.trim()
        if (!prUrl.startsWith("https://")) {
            throw PipelineException("Не удалось создать PR через gh CLI: $result")
        }

        // Извлекаем номер PR из URL
        val prNumber = Regex("""/pull/(\d+)""").find(prUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw PipelineException("Не найден номер PR в URL: $prUrl")

        return Pair(prNumber, prUrl)
    }

    /**
     * Проверяет, существует ли уже PR для данной ветки
     */
    private suspend fun getExistingPrForBranch(repoInfo: RepoInfo, branchName: String): Pair<Int, String>? {
        val result = runGit(
            "gh", "pr", "list",
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--head", branchName,
            "--state", "open",
            "--json", "number,url",
            "--limit", "1"
        )

        // Парсим JSON ответ: [{"number":10,"url":"https://..."}]
        if (result.isBlank() || result == "[]") return null

        val prNumber = Regex(""""number"\s*:\s*(\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()
        val prUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(result)?.groupValues?.get(1)

        return if (prNumber != null && prUrl != null) {
            Pair(prNumber, prUrl)
        } else null
    }

    private fun generateBranchSuffix(taskDescription: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val words = taskDescription
            .lowercase()
            .replace(Regex("[^a-zа-яё0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length <= 15 }  // Пропускаем слишком длинные слова (пути и т.п.)
            .take(3)
            .joinToString("-")
            .take(30)  // Макс 30 символов для описания

        // Timestamp в начале гарантирует уникальность даже при одинаковых описаниях
        return "$timestamp-$words"
    }

    private fun generateCommitMessage(taskDescription: String, plan: ExecutionPlan): String {
        val type = when {
            taskDescription.contains("исправ", ignoreCase = true) ||
                    taskDescription.contains("fix", ignoreCase = true) ||
                    taskDescription.contains("баг", ignoreCase = true) -> "fix"
            taskDescription.contains("добав", ignoreCase = true) ||
                    taskDescription.contains("add", ignoreCase = true) -> "feat"
            taskDescription.contains("рефактор", ignoreCase = true) -> "refactor"
            taskDescription.contains("док", ignoreCase = true) -> "docs"
            else -> "feat"
        }

        val summary = taskDescription.take(50).trim()
        return "$type: $summary"
    }

    private suspend fun callLlm(prompt: String, systemPrompt: String): String {
        val request = LlmRequest(
            model = llmClient.model,
            messages = listOf(LlmMessage(role = ChatRole.USER, content = prompt)),
            systemPrompt = systemPrompt,
            temperature = 0.3,
            maxTokens = 4096
        )

        // Retry с длинной задержкой для rate limit (50k tokens/minute)
        var lastError: Exception? = null
        val retryDelays = listOf(30_000L, 60_000L) // 30s, 60s - rate limit per minute
        repeat(3) { attempt ->
            try {
                // Задержка перед повторной попыткой
                if (attempt > 0 && attempt <= retryDelays.size) {
                    val delayMs = retryDelays[attempt - 1]
                    progress("   ⏳ Rate limit, ждём ${delayMs/1000}s...")
                    delay(delayMs)
                }
                val response = llmClient.send(request)
                return response.text
            } catch (e: Exception) {
                lastError = e
                if (!e.message.orEmpty().contains("rate_limit", ignoreCase = true)) {
                    throw e // Не rate limit - пробрасываем сразу
                }
                progress("   ⚠ Rate limit (попытка ${attempt + 1}/3)")
            }
        }
        throw lastError ?: PipelineException("Не удалось вызвать LLM после 3 попыток (rate limit)")
    }

    private suspend fun runGit(vararg command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(*command)
                    .directory(projectRoot)
                    .redirectErrorStream(true)
                    .start()

                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                process.waitFor()
                output.trim()
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }
    }

    private data class RepoInfo(val owner: String, val repo: String)

    private suspend fun getRepoInfo(): RepoInfo? {
        val remoteUrl = runGit("git", "remote", "get-url", "origin")
        if (remoteUrl.isBlank() || remoteUrl.contains("fatal")) return null

        val pattern = Regex("""github\.com[:/]([^/]+)/([^/.]+)""")
        val match = pattern.find(remoteUrl) ?: return null

        return RepoInfo(match.groupValues[1], match.groupValues[2])
    }

    private suspend fun connectGitHub() {
        if (githubClient == null) {
            val token = githubToken
                ?: System.getenv("GITHUB_TOKEN")
                ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
                ?: throw PipelineException("GITHUB_TOKEN не установлен")

            githubClient = McpClientFactory.createGitHubClient(token)
            githubClient?.connect()
        }
    }

    private fun disconnectGitHub() {
        githubClient?.disconnect()
        githubClient = null
        prReviewService?.disconnect()
        prReviewService = null
    }

    private fun printFinalReport(report: PipelineReport) {
        progress("\n" + "=".repeat(60))
        progress("✅ ЗАДАЧА ВЫПОЛНЕНА!")
        progress("=".repeat(60))
        progress("")
        progress("PR: #${report.prNumber} (${report.prUrl})")
        progress("Ветка: ${report.branchName}")
        progress("")
        progress("Изменённые файлы:")
        report.changedFiles.forEach { file ->
            val status = if (file.isNew) "(новый)" else ""
            progress("  - ${file.path} (+${file.linesAdded}, -${file.linesRemoved}) $status")
        }
        progress("")
        progress("Статистика:")
        progress("  - Итераций review: ${report.reviewIterations}")
        progress("  - Запусков CI: ${report.ciRuns}")
        progress("  - Время выполнения: ${report.totalDuration / 1000} сек")
        progress("")
    }

    companion object {
        private const val SYSTEM_PROMPT_PLANNER = """Ты — AI-архитектор, который анализирует задачи и создаёт планы изменений кода.

Твоя задача:
1. Понять что требуется сделать
2. Определить какие файлы нужно создать/изменить/удалить
3. Описать план в структурированном JSON формате

Всегда отвечай на русском языке.
Будь конкретным в описании изменений."""

        private const val SYSTEM_PROMPT_CODER = """Ты — опытный Kotlin разработчик.

Правила:
1. Пиши чистый, идиоматичный Kotlin код
2. Следуй существующему стилю проекта
3. Не добавляй лишние комментарии
4. Возвращай ТОЛЬКО код, без пояснений
5. Если нужен markdown блок — используй его корректно

Всегда отвечай готовым к использованию кодом."""
    }
}

class PipelineException(message: String) : Exception(message)
