package org.example.app.commands

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.prreview.PrReviewService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Команда для автоматического создания PR с ревью.
 *
 * Полный цикл:
 * 1. git status - показать изменения
 * 2. git add - добавить файлы
 * 3. git commit - создать коммит
 * 4. git push - запушить на remote
 * 5. create PR - создать Pull Request на GitHub
 * 6. auto-review - автоматически сделать ревью
 * 7. post comments - опубликовать комментарии
 *
 * Использование:
 *   /auto-pr                     - интерактивный режим
 *   /auto-pr "commit message"    - с указанием сообщения коммита
 */
class AutoPrCommand(
    private val llmClient: LlmClient,
    private val ragService: RagService? = null
) : Command {

    private val json = McpClientFactory.createJson()

    override fun matches(input: String): Boolean {
        return input.trim().startsWith("/auto-pr") ||
               input.trim().startsWith("/autopr")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val args = input.trim()
            .removePrefix("/auto-pr")
            .removePrefix("/autopr")
            .trim()

        println("\n" + "=".repeat(60))
        println("AUTO PR: Создание PR с автоматическим ревью")
        println("=".repeat(60))

        // 1. Проверяем наличие GitHub токена
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("APPLICATION_GITHUB_TOKEN")
            ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")

        if (githubToken.isNullOrBlank()) {
            printError("GITHUB_TOKEN не установлен!")
            printError("Установите переменную окружения GITHUB_TOKEN или GITHUB_PERSONAL_ACCESS_TOKEN")
            return CommandResult.Continue
        }

        // 2. Получаем информацию о репозитории
        val repoInfo = getRepoInfo()
        if (repoInfo == null) {
            printError("Не удалось определить информацию о репозитории")
            printError("Убедитесь что вы в git репозитории с настроенным remote")
            return CommandResult.Continue
        }

        println("\nРепозиторий: ${repoInfo.owner}/${repoInfo.repo}")
        println("Текущая ветка: ${repoInfo.currentBranch}")

        // 3. Показываем статус
        println("\n--- Git Status ---")
        val status = runGitCommand("git", "status", "--short")
        if (status.isBlank()) {
            printWarning("Нет изменений для коммита")
            return CommandResult.Continue
        }
        println(status)

        // 4. Спрашиваем commit message
        val commitMessage = if (args.isNotBlank() && !args.startsWith("-")) {
            args.removeSurrounding("\"").removeSurrounding("'")
        } else {
            print("\nВведите commit message: ")
            System.out.flush()
            readlnOrNull()?.trim() ?: ""
        }

        if (commitMessage.isBlank()) {
            printError("Commit message не может быть пустым")
            return CommandResult.Continue
        }

        // 5. Спрашиваем base branch
        print("Base branch для PR (Enter для main): ")
        System.out.flush()
        val baseBranch = readlnOrNull()?.trim()?.ifBlank { "main" } ?: "main"

        // 6. Спрашиваем название ветки (если на main)
        var targetBranch = repoInfo.currentBranch
        if (targetBranch == "main" || targetBranch == "master" || targetBranch == baseBranch) {
            print("Вы на $targetBranch. Создать новую ветку? (название или Enter для пропуска): ")
            System.out.flush()
            val newBranch = readlnOrNull()?.trim()
            if (!newBranch.isNullOrBlank()) {
                println("\nСоздаю ветку: $newBranch")
                val createResult = runGitCommand("git", "checkout", "-b", newBranch)
                if (createResult.contains("error") || createResult.contains("fatal")) {
                    printError("Ошибка создания ветки: $createResult")
                    return CommandResult.Continue
                }
                targetBranch = newBranch
                println("✓ Ветка создана и переключена")
            }
        }

        // 7. Подтверждение
        println("\n--- Подтверждение ---")
        println("Commit message: $commitMessage")
        println("Ветка: $targetBranch → $baseBranch")
        println("Действия: add → commit → push → create PR → review")
        print("\nПродолжить? (y/n): ")
        System.out.flush()

        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm != "y" && confirm != "yes" && confirm != "да") {
            println("Отменено")
            return CommandResult.Continue
        }

        // === ВЫПОЛНЕНИЕ ===

        println("\n--- Выполнение ---")

        // 8. Git add
        print("1. git add... ")
        System.out.flush()
        val addResult = runGitCommand("git", "add", "-A")
        println("✓")

        // 9. Git commit
        print("2. git commit... ")
        System.out.flush()
        val commitResult = runGitCommand("git", "commit", "-m", commitMessage)
        if (commitResult.contains("nothing to commit")) {
            printWarning("Нечего коммитить")
            return CommandResult.Continue
        }
        println("✓")

        // 10. Git push
        print("3. git push... ")
        System.out.flush()
        val pushResult = runGitCommand("git", "push", "-u", "origin", targetBranch)
        if (pushResult.contains("error") || pushResult.contains("fatal")) {
            printError("\nОшибка push: $pushResult")
            return CommandResult.Continue
        }
        println("✓")

        // 11. Создаём PR через GitHub MCP
        print("4. Создание PR... ")
        System.out.flush()

        var githubClient: McpClient? = null
        var prNumber: Int? = null
        var prUrl: String? = null

        try {
            githubClient = McpClientFactory.createGitHubClient(githubToken)
            githubClient.connect()

            val prResult = githubClient.callTool(
                "create_pull_request",
                mapOf(
                    "owner" to JsonPrimitive(repoInfo.owner),
                    "repo" to JsonPrimitive(repoInfo.repo),
                    "title" to JsonPrimitive(commitMessage),
                    "body" to JsonPrimitive("Автоматически созданный PR\n\n**Изменения:**\n$status"),
                    "head" to JsonPrimitive(targetBranch),
                    "base" to JsonPrimitive(baseBranch)
                )
            )

            val prContent = prResult.content.firstOrNull()?.text ?: ""

            // Парсим номер PR из ответа
            prNumber = extractPrNumber(prContent)
            prUrl = extractPrUrl(prContent, repoInfo.owner, repoInfo.repo, prNumber)

            if (prNumber != null) {
                println("✓ PR #$prNumber")
            } else {
                println("✓")
            }

        } catch (e: Exception) {
            printError("\nОшибка создания PR: ${e.message}")
            githubClient?.disconnect()
            return CommandResult.Continue
        }

        // 12. Автоматическое ревью
        if (prNumber != null) {
            print("5. AI Review... ")
            System.out.flush()

            try {
                val prReviewService = PrReviewService(
                    llmClient = llmClient,
                    ragService = ragService,
                    githubToken = githubToken
                )

                prReviewService.connect()

                val reviewResult = prReviewService.reviewPr(
                    owner = repoInfo.owner,
                    repo = repoInfo.repo,
                    prNumber = prNumber,
                    useRag = ragService != null
                )

                println("✓")

                // Показываем результат ревью
                println("\n--- Review Result ---")
                println("Score: ${reviewResult.overallScore}")
                println("Summary: ${reviewResult.summary.take(200)}...")

                if (reviewResult.comments.isNotEmpty()) {
                    println("\nНайдено замечаний: ${reviewResult.comments.size}")
                    reviewResult.comments.groupBy { it.severity }.forEach { (severity, comments) ->
                        println("  $severity: ${comments.size}")
                    }
                }

                // 13. Публикуем ревью
                print("6. Публикация ревью... ")
                System.out.flush()

                val posted = prReviewService.submitReview(
                    owner = repoInfo.owner,
                    repo = repoInfo.repo,
                    prNumber = prNumber,
                    result = reviewResult
                )

                if (posted) {
                    println("✓")
                } else {
                    printWarning("Частично")
                }

                prReviewService.disconnect()

            } catch (e: Exception) {
                printWarning("\nОшибка ревью: ${e.message}")
            }
        }

        githubClient?.disconnect()

        // Итог
        println("\n" + "=".repeat(60))
        println("ГОТОВО!")
        println("=".repeat(60))
        if (prUrl != null) {
            println("PR: $prUrl")
        } else if (prNumber != null) {
            println("PR: https://github.com/${repoInfo.owner}/${repoInfo.repo}/pull/$prNumber")
        }
        println()

        return CommandResult.Continue
    }

    // === Helper functions ===

    private data class RepoInfo(
        val owner: String,
        val repo: String,
        val currentBranch: String
    )

    private suspend fun getRepoInfo(): RepoInfo? {
        // Получаем remote URL
        val remoteUrl = runGitCommand("git", "remote", "get-url", "origin")
        if (remoteUrl.isBlank() || remoteUrl.contains("fatal")) return null

        // Парсим owner/repo из URL
        // Форматы:
        //   https://github.com/owner/repo.git
        //   git@github.com:owner/repo.git
        val pattern = Regex("""github\.com[:/]([^/]+)/([^/.]+)""")
        val match = pattern.find(remoteUrl) ?: return null

        val owner = match.groupValues[1]
        val repo = match.groupValues[2]

        // Получаем текущую ветку
        val branch = runGitCommand("git", "branch", "--show-current").trim()

        return RepoInfo(owner, repo, branch)
    }

    private suspend fun runGitCommand(vararg command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(*command)
                    .directory(File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start()

                val output = BufferedReader(InputStreamReader(process.inputStream))
                    .readText()

                process.waitFor()
                output.trim()
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }
    }

    private fun extractPrNumber(content: String): Int? {
        // Ищем "number": 123 или "number":123 в JSON
        val pattern = Regex(""""number"\s*:\s*(\d+)""")
        return pattern.find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPrUrl(content: String, owner: String, repo: String, prNumber: Int?): String? {
        // Ищем html_url в ответе
        val urlPattern = Regex(""""html_url"\s*:\s*"([^"]+)"""")
        val match = urlPattern.find(content)
        if (match != null) {
            return match.groupValues[1]
        }

        // Или строим сами
        return prNumber?.let { "https://github.com/$owner/$repo/pull/$it" }
    }

    private fun printError(message: String) {
        println("\u001B[31m✗ $message\u001B[0m")
    }

    private fun printWarning(message: String) {
        println("\u001B[33m⚠ $message\u001B[0m")
    }
}
