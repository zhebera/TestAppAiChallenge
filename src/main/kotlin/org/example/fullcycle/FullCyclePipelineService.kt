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

            // === ЭТАП 4.5: Локальная валидация перед коммитом ===
            progress("\n🔍 Проверяю изменения локально...")
            changeState(PipelineState.Validating)

            // Проверяем компиляцию с автоисправлением
            val compileOk = validateAndFixCompilation(taskDescription, ragContext, config.maxCompilationAttempts)
            if (!compileOk) {
                progress("\n❌ Не удалось исправить ошибки компиляции")
                progress("   Откатываю изменения...")
                // Откатываем изменения
                runGit("git", "checkout", "--", ".")
                return PipelineReport(
                    success = false,
                    summary = "Ошибки компиляции не удалось исправить автоматически",
                    errors = listOf("Компиляция не прошла после ${config.maxCompilationAttempts} попыток автоисправления")
                )
            }
            progress("   ✓ Компиляция успешна")

            // Опционально: проверяем тесты (можно отключить в config)
            if (config.runLocalTests) {
                val testsOk = validateAndFixTests(taskDescription, ragContext, config.maxTestAttempts)
                if (!testsOk) {
                    progress("   ⚠ Некоторые тесты не прошли, но продолжаем (CI проверит)")
                    errors.add("Локальные тесты не прошли")
                } else {
                    progress("   ✓ Тесты прошли")
                }
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

            // === ЭТАП 7: Self-Review цикл с умным определением застревания ===
            // Пропускаем self-review если все операции - только удаления (нечего ревьюить)
            val hasNonDeleteChanges = plan.plannedChanges.any { it.changeType != ChangeType.DELETE }
            var approved = !hasNonDeleteChanges // Если только DELETE - сразу approved
            if (!hasNonDeleteChanges) {
                progress("\n✓ Self-review пропущен (только удаление файлов)")
            }

            // Для определения застревания храним историю замечаний
            val previousCommentSignatures = mutableListOf<Set<String>>()
            var consecutiveMinorOnlyIterations = 0

            while (!approved && reviewIterations < config.maxReviewIterations) {
                reviewIterations++
                progress("\n🔎 Self-review итерация $reviewIterations...")
                changeState(PipelineState.Reviewing(reviewIterations, config.maxReviewIterations))

                val reviewResult = performSelfReview(repoInfo, prNumber)

                if (reviewResult.approved) {
                    progress("   ✓ Код одобрен!")
                    approved = true
                } else {
                    val criticalCount = reviewResult.comments.count {
                        it.severity == IssueSeverity.CRITICAL || it.severity == IssueSeverity.WARNING
                    }
                    val minorCount = reviewResult.comments.count {
                        it.severity == IssueSeverity.SUGGESTION || it.severity == IssueSeverity.NITPICK
                    }

                    progress("   Найдено замечаний: ${reviewResult.comments.size} (критичных: $criticalCount, minor: $minorCount)")

                    // Создаём "сигнатуру" текущих замечаний для детекции застревания
                    val currentSignature = reviewResult.comments.map { "${it.file}:${it.line}:${it.message.take(50)}" }.toSet()

                    // Проверяем застревание: те же самые замечания повторяются
                    val isStuck = previousCommentSignatures.any { prev ->
                        // Если 80%+ замечаний совпадают - считаем застреванием
                        val intersection = prev.intersect(currentSignature)
                        intersection.size >= (currentSignature.size * 0.8).toInt() && currentSignature.isNotEmpty()
                    }

                    // Если нет критичных замечаний - увеличиваем счётчик
                    if (criticalCount == 0) {
                        consecutiveMinorOnlyIterations++
                    } else {
                        consecutiveMinorOnlyIterations = 0
                    }

                    // Умное решение об одобрении:
                    // 1. Если застряли на тех же замечаниях - одобряем если нет критичных
                    // 2. Если 3+ итерации только minor замечания - одобряем
                    // 3. Если итерация >= 5 и нет критичных - одобряем
                    val shouldForceApprove = when {
                        isStuck && criticalCount == 0 -> {
                            progress("   ⚠ Обнаружено застревание (те же замечания повторяются)")
                            true
                        }
                        consecutiveMinorOnlyIterations >= 3 -> {
                            progress("   ⚠ 3+ итерации только minor замечания")
                            true
                        }
                        reviewIterations >= 5 && criticalCount == 0 -> {
                            progress("   ⚠ 5+ итераций без критичных замечаний")
                            true
                        }
                        else -> false
                    }

                    if (shouldForceApprove) {
                        progress("   ✓ Принудительное одобрение (minor замечания игнорируются)")
                        approved = true
                        continue
                    }

                    // Сохраняем сигнатуру для следующей итерации
                    previousCommentSignatures.add(currentSignature)
                    if (previousCommentSignatures.size > 3) {
                        previousCommentSignatures.removeAt(0) // Храним только последние 3
                    }

                    changeState(PipelineState.FixingReviewComments(reviewIterations, reviewResult.comments.size))

                    // Исправляем только критичные замечания
                    val criticalComments = reviewResult.comments.filter {
                        it.severity == IssueSeverity.CRITICAL || it.severity == IssueSeverity.WARNING
                    }
                    val resultToFix = reviewResult.copy(comments = criticalComments)

                    if (criticalComments.isEmpty()) {
                        progress("   Нет критичных замечаний для исправления")
                        approved = true
                        continue
                    }

                    val fixed = fixReviewComments(taskDescription, resultToFix, ragContext)
                    if (fixed) {
                        progress("   Коммит исправлений...")
                        // Добавляем только файлы из замечаний
                        val filesWithIssues = criticalComments.map { it.file }.distinct()
                        for (file in filesWithIssues) {
                            runGit("git", "add", file)
                        }
                        runGit("git", "commit", "-m", "fix: исправлены замечания review (итерация $reviewIterations)")
                        runGit("git", "push")
                    } else {
                        progress("   Не удалось исправить замечания")
                        // Даже если не удалось исправить - продолжаем, может CI пройдёт
                    }
                }
            }

            if (!approved && reviewIterations >= config.maxReviewIterations) {
                progress("   ⚠ Достигнут лимит итераций, продолжаем без полного одобрения")
                // Не прерываем - пусть CI решит
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

    /**
     * Очищает ответ LLM от markdown артефактов.
     * Агрессивно удаляет все возможные варианты markdown разметки.
     */
    private fun cleanCodeResponse(response: String): String {
        var code = response.trim()

        // 1. Удаляем markdown блоки ```kotlin ... ``` или ```...```
        // Может быть несколько блоков, берём содержимое первого
        val codeBlockPattern = Regex("```(?:kotlin|kt|java|gradle)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val match = codeBlockPattern.find(code)
        if (match != null) {
            code = match.groupValues[1]
        } else {
            // 2. Если нет закрывающего ```, но есть открывающий в начале - убираем его
            val startPattern = Regex("^\\s*```(?:kotlin|kt|java|gradle)?\\s*\\n?", RegexOption.IGNORE_CASE)
            code = code.replace(startPattern, "")

            // 3. Убираем закрывающий ``` в конце если остался
            code = code.replace(Regex("\\s*```\\s*$"), "")
        }

        // 4. Убираем случайные одиночные ``` которые могут остаться
        code = code.replace(Regex("^```\\w*\\s*$", RegexOption.MULTILINE), "")

        // 5. Удаляем markdown заголовки в начале если LLM их добавил
        code = code.replace(Regex("^#+\\s+.*\\n"), "")

        // 6. Удаляем "Here's the code:" и подобные фразы в начале
        code = code.replace(Regex("^(?:Here'?s?|Below is|The following).*?:\\s*\\n", RegexOption.IGNORE_CASE), "")

        // 7. Финальная очистка пробелов
        return code.trim()
    }

    // ==================== ЛОКАЛЬНАЯ ВАЛИДАЦИЯ С АВТОИСПРАВЛЕНИЕМ ====================

    /**
     * Результат локальной валидации (компиляция или тесты)
     */
    data class ValidationResult(
        val success: Boolean,
        val errorOutput: String = "",
        val errorFiles: List<String> = emptyList(),  // Файлы с ошибками
        val errorMessages: List<String> = emptyList()  // Отдельные ошибки
    )

    /**
     * Запускает компиляцию и при ошибках пытается исправить автоматически.
     * Возвращает true если компиляция успешна (сразу или после исправлений).
     */
    private suspend fun validateAndFixCompilation(
        taskDescription: String,
        ragContext: String,
        maxAttempts: Int = 3
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            progress("   🔨 Проверка компиляции (попытка ${attempt + 1}/$maxAttempts)...")

            val result = runCompilation()

            if (result.success) {
                if (attempt > 0) {
                    progress("   ✓ Компиляция успешна после исправлений!")
                }
                return true
            }

            progress("   ✗ Ошибки компиляции: ${result.errorMessages.size}")

            // Пытаемся исправить
            val fixed = fixCompilationErrors(taskDescription, result, ragContext)
            if (!fixed) {
                progress("   ⚠ Не удалось автоматически исправить ошибки")
                if (attempt == maxAttempts - 1) {
                    progress("   Логи ошибок:")
                    result.errorOutput.lines().take(20).forEach { line ->
                        progress("      $line")
                    }
                }
            }
        }

        return false
    }

    /**
     * Запускает компиляцию проекта и парсит ошибки.
     */
    private suspend fun runCompilation(): ValidationResult {
        val output = runGit("./gradlew", "compileKotlin", "--console=plain", "-q")

        // Проверяем на успешную компиляцию
        val hasBuildFailed = output.contains("BUILD FAILED", ignoreCase = true) ||
                output.contains("Compilation error", ignoreCase = true) ||
                output.contains("Unresolved reference", ignoreCase = true) ||
                output.contains("error:", ignoreCase = true)

        if (!hasBuildFailed && !output.contains("FAILURE")) {
            return ValidationResult(success = true)
        }

        // Парсим ошибки
        val errorPattern = Regex("e:\\s*([^:]+):(\\d+):(\\d+):\\s*(.+)")
        val errors = mutableListOf<String>()
        val errorFiles = mutableSetOf<String>()

        output.lines().forEach { line ->
            val match = errorPattern.find(line)
            if (match != null) {
                val file = match.groupValues[1]
                val lineNum = match.groupValues[2]
                val message = match.groupValues[4]
                errors.add("$file:$lineNum: $message")
                errorFiles.add(file)
            } else if (line.contains("error:", ignoreCase = true) ||
                line.contains("Unresolved reference", ignoreCase = true)) {
                errors.add(line.trim())
            }
        }

        return ValidationResult(
            success = false,
            errorOutput = output,
            errorFiles = errorFiles.toList(),
            errorMessages = errors
        )
    }

    /**
     * Пытается исправить ошибки компиляции с помощью LLM.
     */
    private suspend fun fixCompilationErrors(
        taskDescription: String,
        result: ValidationResult,
        ragContext: String
    ): Boolean {
        // Группируем ошибки по файлам
        val errorsByFile = mutableMapOf<String, MutableList<String>>()

        // Парсим ошибки формата "file:line: message"
        result.errorMessages.forEach { error ->
            val match = Regex("([^:]+):(\\d+):(.+)").find(error)
            if (match != null) {
                val file = match.groupValues[1].trim()
                val message = match.groupValues[3].trim()
                errorsByFile.getOrPut(file) { mutableListOf() }.add("Line ${match.groupValues[2]}: $message")
            }
        }

        // Если не смогли распарсить файлы, пробуем из errorFiles
        if (errorsByFile.isEmpty() && result.errorFiles.isNotEmpty()) {
            result.errorFiles.forEach { file ->
                errorsByFile[file] = result.errorMessages.toMutableList()
            }
        }

        var anyFixed = false

        for ((filePath, errors) in errorsByFile) {
            val file = File(projectRoot, filePath)
            if (!file.exists()) {
                // Пробуем найти файл в src/main/kotlin
                val altFile = File(projectRoot, "src/main/kotlin/$filePath")
                if (!altFile.exists()) continue
            }

            val actualFile = if (file.exists()) file else File(projectRoot, "src/main/kotlin/$filePath")
            if (!actualFile.exists()) continue

            val currentContent = actualFile.readText()

            val prompt = buildString {
                appendLine("Исправь ошибки компиляции в файле ${actualFile.name}")
                appendLine()
                appendLine("## Ошибки компиляции")
                errors.forEach { appendLine("- $it") }
                appendLine()
                appendLine("## Текущий код файла")
                appendLine("```kotlin")
                appendLine(currentContent.take(15000))
                appendLine("```")
                appendLine()
                appendLine("## Контекст задачи")
                appendLine(taskDescription)
                appendLine()
                appendLine("Верни ПОЛНЫЙ исправленный код файла. БЕЗ markdown блоков и пояснений.")
            }

            try {
                val response = callLlm(prompt, SYSTEM_PROMPT_FIXER)
                val newContent = cleanCodeResponse(response)

                if (newContent.isNotBlank() && newContent != currentContent) {
                    // Защита от truncation
                    val oldLines = currentContent.lines().size
                    val newLines = newContent.lines().size
                    if (oldLines > 50 && newLines < oldLines * 0.5) {
                        progress("      ⚠ Пропущен ${actualFile.name}: размер уменьшился слишком сильно")
                        continue
                    }

                    actualFile.writeText(newContent)
                    progress("      ✓ Исправлен: ${actualFile.name}")
                    anyFixed = true
                }
            } catch (e: Exception) {
                progress("      ⚠ Не удалось исправить ${actualFile.name}: ${e.message}")
            }
        }

        return anyFixed
    }

    /**
     * Запускает тесты и при ошибках пытается исправить автоматически.
     * Возвращает true если тесты проходят (сразу или после исправлений).
     */
    private suspend fun validateAndFixTests(
        taskDescription: String,
        ragContext: String,
        maxAttempts: Int = 3
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            progress("   🧪 Запуск тестов (попытка ${attempt + 1}/$maxAttempts)...")

            val result = runTests()

            if (result.success) {
                if (attempt > 0) {
                    progress("   ✓ Тесты проходят после исправлений!")
                }
                return true
            }

            progress("   ✗ Тесты упали: ${result.errorMessages.size} ошибок")

            // Пытаемся исправить
            val fixed = fixTestErrors(taskDescription, result, ragContext)
            if (!fixed) {
                progress("   ⚠ Не удалось автоматически исправить тесты")
            }
        }

        return false
    }

    /**
     * Запускает тесты и парсит результаты.
     */
    private suspend fun runTests(): ValidationResult {
        val output = runGit("./gradlew", "test", "--console=plain", "-q")

        if (!output.contains("FAILED") && !output.contains("FAILURE")) {
            return ValidationResult(success = true)
        }

        // Парсим упавшие тесты
        val testFailPattern = Regex("(.+)\\s+>\\s+(.+)\\s+FAILED")
        val errors = mutableListOf<String>()
        val errorFiles = mutableSetOf<String>()

        output.lines().forEach { line ->
            val match = testFailPattern.find(line)
            if (match != null) {
                val testClass = match.groupValues[1]
                val testMethod = match.groupValues[2]
                errors.add("$testClass.$testMethod FAILED")
                // Пытаемся найти файл теста
                val testFile = testClass.replace(".", "/") + ".kt"
                errorFiles.add("src/test/kotlin/$testFile")
            }
        }

        // Ищем assertion errors
        val assertionPattern = Regex("expected:\\s*<(.+)>\\s+but was:\\s*<(.+)>")
        output.lines().forEach { line ->
            val match = assertionPattern.find(line)
            if (match != null) {
                errors.add("Assertion failed: expected <${match.groupValues[1]}> but was <${match.groupValues[2]}>")
            }
        }

        return ValidationResult(
            success = false,
            errorOutput = output,
            errorFiles = errorFiles.toList(),
            errorMessages = errors
        )
    }

    /**
     * Пытается исправить упавшие тесты.
     */
    private suspend fun fixTestErrors(
        taskDescription: String,
        result: ValidationResult,
        ragContext: String
    ): Boolean {
        val prompt = buildString {
            appendLine("Тесты упали. Проанализируй ошибки и исправь код.")
            appendLine()
            appendLine("## Ошибки тестов")
            result.errorMessages.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Полный вывод")
            appendLine("```")
            appendLine(result.errorOutput.take(5000))
            appendLine("```")
            appendLine()
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()
            appendLine("Опиши какой файл нужно исправить и верни исправленный код.")
            appendLine("Формат ответа:")
            appendLine("FILE: путь/к/файлу.kt")
            appendLine("```kotlin")
            appendLine("исправленный код")
            appendLine("```")
        }

        try {
            val response = callLlm(prompt, SYSTEM_PROMPT_FIXER)

            // Парсим ответ - ищем FILE: и код
            val filePattern = Regex("FILE:\\s*(.+\\.kt)")
            val fileMatch = filePattern.find(response)

            if (fileMatch != null) {
                val filePath = fileMatch.groupValues[1].trim()
                val file = File(projectRoot, filePath)

                if (file.exists()) {
                    val codeMatch = Regex("```kotlin\\s*\\n([\\s\\S]*?)```").find(response)
                    if (codeMatch != null) {
                        val newContent = codeMatch.groupValues[1].trim()
                        val oldContent = file.readText()

                        // Защита от truncation
                        val oldLines = oldContent.lines().size
                        val newLines = newContent.lines().size
                        if (oldLines > 50 && newLines < oldLines * 0.5) {
                            return false
                        }

                        file.writeText(newContent)
                        progress("      ✓ Исправлен: $filePath")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            progress("      ⚠ Ошибка при исправлении тестов: ${e.message}")
        }

        return false
    }

    // ==================== END ЛОКАЛЬНАЯ ВАЛИДАЦИЯ ====================

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

    // ==================== CI LOGS & FIX ====================

    /**
     * Получает логи последнего неуспешного CI run для PR.
     * Использует gh CLI для доступа к GitHub Actions.
     */
    private suspend fun fetchCILogs(repoInfo: RepoInfo, prNumber: Int): String? {
        try {
            // Получаем список workflow runs для PR
            val runsResult = runGit(
                "gh", "run", "list",
                "--repo", "${repoInfo.owner}/${repoInfo.repo}",
                "--branch", runGit("git", "branch", "--show-current").trim(),
                "--status", "failure",
                "--json", "databaseId,conclusion,status",
                "--limit", "1"
            )

            if (runsResult.isBlank() || runsResult == "[]") {
                // Пробуем completed runs
                val completedRuns = runGit(
                    "gh", "run", "list",
                    "--repo", "${repoInfo.owner}/${repoInfo.repo}",
                    "--branch", runGit("git", "branch", "--show-current").trim(),
                    "--json", "databaseId,conclusion,status",
                    "--limit", "1"
                )
                if (completedRuns.isBlank() || completedRuns == "[]") return null

                val runId = Regex(""""databaseId"\s*:\s*(\d+)""").find(completedRuns)?.groupValues?.get(1)
                    ?: return null

                return fetchRunLogs(repoInfo, runId)
            }

            val runId = Regex(""""databaseId"\s*:\s*(\d+)""").find(runsResult)?.groupValues?.get(1)
                ?: return null

            return fetchRunLogs(repoInfo, runId)
        } catch (e: Exception) {
            progress("   ⚠ Не удалось получить логи CI: ${e.message}")
            return null
        }
    }

    /**
     * Получает логи конкретного workflow run.
     */
    private suspend fun fetchRunLogs(repoInfo: RepoInfo, runId: String): String? {
        // gh run view показывает детали run, включая failed steps
        val viewResult = runGit(
            "gh", "run", "view", runId,
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--log-failed"  // Показать логи только упавших шагов
        )

        if (viewResult.isNotBlank() && !viewResult.contains("error:")) {
            return viewResult
        }

        // Fallback: получаем все логи
        val fullLogs = runGit(
            "gh", "run", "view", runId,
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--log"
        )

        return if (fullLogs.isNotBlank() && !fullLogs.contains("error:")) {
            // Берём последние 200 строк (обычно там ошибки)
            fullLogs.lines().takeLast(200).joinToString("\n")
        } else null
    }

    /**
     * Исправляет ошибки CI, используя реальные логи GitHub Actions.
     * Полноценная реализация с анализом логов и автоисправлением.
     */
    private suspend fun fixCIError(
        taskDescription: String,
        ciResult: CIResult,
        ragContext: String
    ): Boolean {
        val repoInfo = getRepoInfo() ?: return false

        // Получаем реальные логи CI
        progress("   Получаю логи CI...")
        val ciLogs = if (ciResult.logs.isNullOrBlank()) {
            fetchCILogs(repoInfo, 0) // PR number not needed for branch-based lookup
        } else {
            ciResult.logs
        }

        if (ciLogs.isNullOrBlank()) {
            progress("   ⚠ Не удалось получить логи CI")
            // Пробуем локальную компиляцию/тесты
            return runLocalValidation(taskDescription, ragContext)
        }

        progress("   Анализирую ошибки в логах...")

        // Парсим ошибки из логов
        val errorAnalysis = analyzeCILogs(ciLogs)

        val prompt = buildString {
            appendLine("CI pipeline упал. Проанализируй логи и исправь ошибки.")
            appendLine()
            appendLine("## Анализ ошибок")
            appendLine("Тип ошибки: ${errorAnalysis.errorType}")
            if (errorAnalysis.failedFiles.isNotEmpty()) {
                appendLine("Файлы с ошибками: ${errorAnalysis.failedFiles.joinToString(", ")}")
            }
            appendLine()
            appendLine("## Логи CI (последние сообщения)")
            appendLine("```")
            appendLine(ciLogs.take(8000))
            appendLine("```")
            appendLine()
            appendLine("## Задача")
            appendLine(taskDescription)
            appendLine()
            appendLine("Верни исправленный код для каждого файла с ошибкой.")
            appendLine("Формат:")
            appendLine("FILE: путь/к/файлу.kt")
            appendLine("```kotlin")
            appendLine("полный исправленный код")
            appendLine("```")
        }

        try {
            val response = callLlm(prompt, SYSTEM_PROMPT_FIXER)

            // Парсим и применяем исправления
            val filePattern = Regex("FILE:\\s*(.+\\.kt)")
            val codePattern = Regex("```(?:kotlin)?\\s*\\n([\\s\\S]*?)```")

            var anyFixed = false
            var currentPos = 0

            while (true) {
                val fileMatch = filePattern.find(response, currentPos) ?: break
                val filePath = fileMatch.groupValues[1].trim()
                currentPos = fileMatch.range.last

                val codeMatch = codePattern.find(response, currentPos) ?: break
                val newCode = codeMatch.groupValues[1].trim()
                currentPos = codeMatch.range.last

                val file = File(projectRoot, filePath)
                if (file.exists() && newCode.isNotBlank()) {
                    val oldContent = file.readText()
                    val oldLines = oldContent.lines().size
                    val newLines = newCode.lines().size

                    // Защита от truncation
                    if (oldLines > 50 && newLines < oldLines * 0.5) {
                        progress("      ⚠ Пропущен $filePath: размер уменьшился слишком сильно")
                        continue
                    }

                    file.writeText(newCode)
                    progress("      ✓ Исправлен: $filePath")
                    anyFixed = true
                }
            }

            return anyFixed
        } catch (e: Exception) {
            progress("   ⚠ Ошибка при исправлении CI: ${e.message}")
            return false
        }
    }

    /**
     * Запускает локальную валидацию как fallback если не удалось получить CI логи.
     */
    private suspend fun runLocalValidation(taskDescription: String, ragContext: String): Boolean {
        progress("   Запускаю локальную проверку...")

        // Сначала пробуем компиляцию
        val compileResult = runCompilation()
        if (!compileResult.success) {
            return fixCompilationErrors(taskDescription, compileResult, ragContext)
        }

        // Потом тесты
        val testResult = runTests()
        if (!testResult.success) {
            return fixTestErrors(taskDescription, testResult, ragContext)
        }

        return true // Локально всё ок
    }

    /**
     * Анализ логов CI для определения типа ошибки.
     */
    private data class CIErrorAnalysis(
        val errorType: String,
        val failedFiles: List<String>,
        val errorMessages: List<String>
    )

    private fun analyzeCILogs(logs: String): CIErrorAnalysis {
        val failedFiles = mutableSetOf<String>()
        val errorMessages = mutableListOf<String>()
        var errorType = "unknown"

        // Определяем тип ошибки
        when {
            logs.contains("compileKotlin FAILED", ignoreCase = true) ||
            logs.contains("Compilation error", ignoreCase = true) ||
            logs.contains("Unresolved reference", ignoreCase = true) -> {
                errorType = "compilation"

                // Парсим ошибки компиляции
                val errorPattern = Regex("e:\\s*([^:]+):(\\d+):\\d+:\\s*(.+)")
                errorPattern.findAll(logs).forEach { match ->
                    failedFiles.add(match.groupValues[1])
                    errorMessages.add("${match.groupValues[1]}:${match.groupValues[2]}: ${match.groupValues[3]}")
                }
            }
            logs.contains("test FAILED", ignoreCase = true) ||
            logs.contains("FAILED", ignoreCase = true) && logs.contains("test", ignoreCase = true) -> {
                errorType = "test"

                // Парсим упавшие тесты
                val testPattern = Regex("(.+)\\s+>\\s+(.+)\\s+FAILED")
                testPattern.findAll(logs).forEach { match ->
                    val testClass = match.groupValues[1]
                    failedFiles.add("src/test/kotlin/${testClass.replace(".", "/")}.kt")
                    errorMessages.add("${match.groupValues[1]}.${match.groupValues[2]} FAILED")
                }
            }
            logs.contains("lint", ignoreCase = true) ||
            logs.contains("checkstyle", ignoreCase = true) ||
            logs.contains("ktlint", ignoreCase = true) -> {
                errorType = "lint"
            }
            else -> {
                errorType = "unknown"
            }
        }

        return CIErrorAnalysis(errorType, failedFiles.toList(), errorMessages)
    }

    // ==================== END CI LOGS & FIX ====================

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

    /**
     * Мерджит PR через gh CLI (MCP не имеет merge инструмента).
     */
    private suspend fun mergePullRequest(repoInfo: RepoInfo, prNumber: Int) {
        val result = runGit(
            "gh", "pr", "merge", prNumber.toString(),
            "--repo", "${repoInfo.owner}/${repoInfo.repo}",
            "--squash",
            "--delete-branch"
        )

        if (result.contains("error") || result.contains("failed")) {
            throw PipelineException("Ошибка merge PR #$prNumber: $result")
        }
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
5. НЕ используй markdown блоки (``` и т.д.) — возвращай чистый код

Всегда отвечай готовым к использованию кодом."""

        private const val SYSTEM_PROMPT_FIXER = """Ты — опытный Kotlin разработчик, специализирующийся на исправлении ошибок.

Твоя задача:
1. Проанализировать ошибки компиляции или тестов
2. Найти причину ошибки в коде
3. Исправить код минимальными изменениями

Правила:
1. Исправляй ТОЛЬКО то, что вызывает ошибку
2. Не меняй логику работы без необходимости
3. Сохраняй стиль существующего кода
4. Возвращай ПОЛНЫЙ код файла (не фрагменты)
5. НЕ используй markdown блоки — возвращай чистый код

Будь точным и лаконичным."""
    }
}

class PipelineException(message: String) : Exception(message)
