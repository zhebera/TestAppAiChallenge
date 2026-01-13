package org.example.app.commands

import kotlinx.coroutines.runBlocking
import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.prreview.PrReviewService

/**
 * Команда для ревью Pull Request.
 *
 * Использование:
 *   /review-pr <url>              - Провести ревью PR по URL
 *   /review-pr <owner/repo> <num> - Провести ревью PR по owner/repo и номеру
 *
 * Примеры:
 *   /review-pr https://github.com/owner/repo/pull/123
 *   /review-pr owner/repo 123
 */
class ReviewPrCommand(
    private val llmClient: LlmClient,
    private val ragService: RagService? = null
) : Command {

    override fun matches(input: String): Boolean {
        return input.trim().startsWith("/review-pr") ||
               input.trim().startsWith("/pr-review") ||
               input.trim().startsWith("/review")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val args = input.trim().removePrefix("/review-pr")
            .removePrefix("/pr-review")
            .removePrefix("/review")
            .trim()

        if (args.isBlank()) {
            printUsage(context)
            return CommandResult.Continue
        }

        // Парсим аргументы
        val (owner, repo, prNumber) = parseArgs(args)
            ?: run {
                context.console.printError("Не удалось распарсить аргументы. Используйте URL или owner/repo number")
                printUsage(context)
                return CommandResult.Continue
            }

        context.console.printInfo("Начинаю ревью PR #$prNumber в $owner/$repo...")
        context.console.printInfo("Подключаюсь к GitHub...")

        val prReviewService = PrReviewService(
            llmClient = llmClient,
            ragService = ragService,
            githubToken = System.getenv("GITHUB_TOKEN") ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
        )

        try {
            // Подключаемся к GitHub
            if (!prReviewService.connect()) {
                context.console.printError("Не удалось подключиться к GitHub. Проверьте GITHUB_TOKEN.")
                return CommandResult.Continue
            }

            context.console.printInfo("Получаю информацию о PR...")

            // Выполняем ревью
            val result = prReviewService.reviewPr(
                owner = owner,
                repo = repo,
                prNumber = prNumber,
                useRag = ragService != null
            )

            // Выводим результат
            printReviewResult(context, result)

            // Спрашиваем, хочет ли пользователь опубликовать ревью
            context.console.printInfo("\nОпубликовать это ревью как комментарий в PR? (y/n)")
            val answer = readlnOrNull()?.trim()?.lowercase()

            if (answer == "y" || answer == "yes" || answer == "да") {
                context.console.printInfo("Публикую ревью...")
                val published = prReviewService.submitReview(owner, repo, prNumber, result)
                if (published) {
                    context.console.printSuccess("Ревью успешно опубликовано!")
                } else {
                    context.console.printError("Не удалось опубликовать ревью")
                }
            }

        } catch (e: Exception) {
            context.console.printError("Ошибка при ревью PR: ${e.message}")
        } finally {
            prReviewService.disconnect()
        }

        return CommandResult.Continue
    }

    private fun parseArgs(args: String): Triple<String, String, Int>? {
        // Пробуем как URL
        PrReviewService.parsePrUrl(args)?.let { return it }

        // Пробуем как owner/repo number
        val parts = args.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val ownerRepo = parts[0]
            val number = parts[1].toIntOrNull() ?: return null

            val ownerRepoParts = ownerRepo.split("/")
            if (ownerRepoParts.size == 2) {
                return Triple(ownerRepoParts[0], ownerRepoParts[1], number)
            }
        }

        return null
    }

    private fun printUsage(context: CommandContext) {
        context.console.printInfo("""
            |
            |Использование команды /review-pr:
            |
            |  /review-pr <url>              - Ревью PR по URL
            |  /review-pr <owner/repo> <num> - Ревью по owner/repo и номеру
            |
            |Примеры:
            |  /review-pr https://github.com/owner/repo/pull/123
            |  /review-pr owner/repo 123
            |
            |Требования:
            |  - Установлена переменная GITHUB_TOKEN или GITHUB_PERSONAL_ACCESS_TOKEN
            |  - Токен имеет права на чтение PR и создание комментариев
            |
        """.trimMargin())
    }

    private fun printReviewResult(context: CommandContext, result: PrReviewService.ReviewResult) {
        context.console.printInfo("\n" + "=".repeat(60))
        context.console.printInfo("CODE REVIEW: ${result.prTitle}")
        context.console.printInfo("PR #${result.prNumber}")
        context.console.printInfo("=".repeat(60))

        context.console.printInfo("\n## Summary")
        context.console.printInfo(result.summary)

        if (result.comments.isNotEmpty()) {
            context.console.printInfo("\n## Comments (${result.comments.size})")

            val grouped = result.comments.groupBy { it.severity }

            grouped["critical"]?.let { critical ->
                context.console.printError("\nCRITICAL (${critical.size}):")
                critical.forEach { c ->
                    context.console.printError("  - ${c.file}${c.line?.let { ":$it" } ?: ""}")
                    context.console.printError("    ${c.message}")
                }
            }

            grouped["warning"]?.let { warnings ->
                context.console.printWarning("\nWARNINGS (${warnings.size}):")
                warnings.forEach { c ->
                    context.console.printWarning("  - ${c.file}${c.line?.let { ":$it" } ?: ""}")
                    context.console.printWarning("    ${c.message}")
                }
            }

            grouped["suggestion"]?.let { suggestions ->
                context.console.printInfo("\nSUGGESTIONS (${suggestions.size}):")
                suggestions.forEach { c ->
                    context.console.printInfo("  - ${c.file}${c.line?.let { ":$it" } ?: ""}")
                    context.console.printInfo("    ${c.message}")
                }
            }

            grouped["nitpick"]?.let { nitpicks ->
                context.console.printInfo("\nNITPICKS (${nitpicks.size}):")
                nitpicks.forEach { c ->
                    context.console.printInfo("  - ${c.file}${c.line?.let { ":$it" } ?: ""}")
                    context.console.printInfo("    ${c.message}")
                }
            }
        }

        context.console.printInfo("\n## Score: ${result.overallScore}")
        context.console.printInfo("=".repeat(60))
    }
}

// Extension functions для ConsoleInput
private fun org.example.presentation.ConsoleInput.printInfo(message: String) {
    println(message)
}

private fun org.example.presentation.ConsoleInput.printSuccess(message: String) {
    println("\u001B[32m$message\u001B[0m")
}

private fun org.example.presentation.ConsoleInput.printWarning(message: String) {
    println("\u001B[33m$message\u001B[0m")
}

private fun org.example.presentation.ConsoleInput.printError(message: String) {
    println("\u001B[31m$message\u001B[0m")
}
