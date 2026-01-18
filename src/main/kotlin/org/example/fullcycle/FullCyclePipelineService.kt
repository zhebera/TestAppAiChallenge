Теперь обновлю FullCyclePipelineService:

```kotlin
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
import java.util.concurrent.TimeoutException

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
    private val timeoutHandler = TimeoutHandler(defaultTimeoutMs = 30000L)

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

                val reviewResult = try {
                    timeoutHandler.withRetryOnTimeout(
                        timeoutMs = 45000L,
                        maxRetries = 2,
                        delayMs = 2000L
                    ) {
                        performSelfReview(repoInfo, prNumber)
                    }
                } catch (e: TimeoutException) {
                    progress("   ⚠️ Таймаут при выполнении self-review: ${e.message}")
                    errors.add("Self-review timeout: ${e.message}")
                    ReviewResult(approved = false, comments = emptyList())
                }

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
                    val ciResult = try {
                        timeoutHandler.withTimeout(
                            timeoutMs = 60000L
                        ) {
                            waitForCI(repoInfo, prNumber)
                        }
                    } catch (e: TimeoutException) {
                        progress("   ⚠️ Таймаут при ожидании CI: ${e.message}")
                        errors.add("CI wait timeout: ${e.message}")
                        CIResult(status = CIStatus.PENDING, errorMessage = "Timeout waiting for CI")
                    }

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
                            val fixed = try {
                                timeoutHandler.withTimeout(
                                    timeoutMs = 45000L
                                ) {
                                    fixCIError(taskDescription, ciResult, ragContext)
                                }
                            } catch (e: TimeoutException) {
                                progress("   ⚠️ Таймаут при исправлении CI ошибки: ${e.message}")
                                errors.add("Fix CI error timeout: ${e.message}")
                                false
                            }

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
                    changeState(PipelineState.NeedsUserInput(
                        "CI не прошёл. Что делать?",
                        listOf("Попробовать ещё раз", "Оставить PR открытым", "Замерджить как есть")
                    ))
                }
            }

            // === ЭТАП 9: Merge в main ===
            progress("\n🎯 Мержу в main...")
            changeState(PipelineState.Merging(prNumber))

            try {
                timeoutHandler.withTimeout(
                    timeoutMs = 30000L
                ) {
                    mergePullRequest(repoInfo, prNumber)
                }
                progress("   ✓ PR успешно замержен!")
            } catch (e: TimeoutException) {
                progress("   ⚠️ Таймаут при мерже PR: ${e.message}")
                errors.add("Merge timeout: ${e.message}")
            }

            // === Финальный отчёт ===
            val duration = System.currentTimeMillis() - startTime
            val success = errors.isEmpty()

            return PipelineReport(
                success = success,
                summary = if (success) {
                    "✓ Полный цикл завершён успешно за ${duration / 1000}с"
                } else {
                    "⚠️ Цикл завершён с ошибками (${errors.size})"
                },
                prNumber = prNumber,
                branchName = branchName,
                reviewIterations = reviewIterations,
                ciRuns = ciRuns,
                errors = errors,
                durationMs = duration
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            progress("\n❌ Ошибка: ${e.message}")
            return PipelineReport(
                success = false,
                summary = "Ошибка: ${e.message}",
                errors = errors + listOf(e.message ?: "Unknown error"),
                durationMs = duration
            )
        } finally {
            disconnectGitHub()
        }
    }

    private suspend fun buildRagContext(taskDescription: String): String {
        return ragService?.search(taskDescription)?.joinToString("\n") { it.content } ?: ""
    }

    private suspend fun generatePlan(taskDescription: String, ragContext: String): ExecutionPlan {
        val prompt = """
            Задача: $taskDescription
            
            Контекст кода:
            $ragContext
            
            Создай детальный план изменений в формате JSON с полями:
            - plannedChanges: массив объектов {filePath, changeType (CREATE/MODIFY/DELETE), description}
            - summary: краткое описание
        """.trimIndent()

        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = ChatRole.USER, content = prompt)
            ),
            temperature = 0.3
        )

        val response = llmClient.complete(request)
        val content = response.choices.firstOrNull()?.message?.content ?: ""

        return try {
            val json = Json.parseToJsonElement(content).jsonObject
            ExecutionPlan(
                plannedChanges = json["plannedChanges"]?.jsonArray?.map { change ->