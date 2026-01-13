package org.example.prreview

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.rag.*
import java.io.File

/**
 * Entry point для запуска PR Review из CI/CD.
 *
 * Использование:
 *   java -cp ... org.example.prreview.PrReviewRunnerKt <owner> <repo> <pr_number> [options]
 *
 * Переменные окружения:
 *   ANTHROPIC_API_KEY - API ключ Anthropic (обязательно)
 *   GITHUB_TOKEN - GitHub токен для работы с PR (обязательно)
 *   PROJECT_ROOT - корень проекта для RAG (опционально, по умолчанию текущая директория)
 *
 * Options:
 *   --no-rag         - отключить RAG контекст
 *   --no-post        - не публиковать ревью в PR (только вывод)
 *   --quick          - быстрый режим (только критические проблемы)
 *   --security       - режим security review
 *   --output <file>  - записать результат в файл
 */
fun main(args: Array<String>) = runBlocking {
    println("=".repeat(60))
    println("AI PR Reviewer")
    println("=".repeat(60))

    // Парсим аргументы
    val config = parseArgs(args)
    if (config == null) {
        printUsage()
        System.exit(1)
        return@runBlocking
    }

    // Проверяем переменные окружения
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    if (anthropicKey.isNullOrBlank()) {
        println("ERROR: ANTHROPIC_API_KEY не установлен")
        System.exit(1)
        return@runBlocking
    }

    val githubToken = System.getenv("GITHUB_TOKEN") ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
    if (githubToken.isNullOrBlank()) {
        println("ERROR: GITHUB_TOKEN не установлен")
        System.exit(1)
        return@runBlocking
    }

    println("Owner: ${config.owner}")
    println("Repo: ${config.repo}")
    println("PR: #${config.prNumber}")
    println("RAG enabled: ${config.useRag}")
    println("Post to PR: ${config.postToPr}")
    println("=".repeat(60))

    // Создаём HTTP клиент
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Создаём LLM клиент
    val llmClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey,
        model = "claude-sonnet-4-20250514"
    )

    // Создаём RAG service (если включён)
    val ragService = if (config.useRag) {
        createRagService(config.projectRoot, httpClient, json)
    } else null

    // Создаём PR Review сервис
    val prReviewService = PrReviewService(
        llmClient = llmClient,
        ragService = ragService,
        githubToken = githubToken
    )

    try {
        // Подключаемся к GitHub
        println("\nПодключаюсь к GitHub MCP Server...")
        if (!prReviewService.connect()) {
            println("ERROR: Не удалось подключиться к GitHub")
            httpClient.close()
            System.exit(1)
            return@runBlocking
        }
        println("Подключение успешно!")

        // Индексируем проект для RAG (если включён)
        if (ragService != null && config.useRag) {
            println("\nИндексирую файлы проекта для RAG...")
            indexProjectFiles(ragService)
        }

        // Выполняем ревью
        println("\nВыполняю code review PR #${config.prNumber}...")
        val result = prReviewService.reviewPr(
            owner = config.owner,
            repo = config.repo,
            prNumber = config.prNumber,
            useRag = config.useRag
        )

        // Выводим результат
        println("\n" + "=".repeat(60))
        println("REVIEW RESULT")
        println("=".repeat(60))

        val reviewOutput = formatReviewOutput(result)
        println(reviewOutput)

        // Записываем в файл (если указан)
        config.outputFile?.let { file ->
            File(file).writeText(reviewOutput)
            println("\nРезультат записан в: $file")
        }

        // Публикуем в PR (если включено)
        if (config.postToPr) {
            println("\nПубликую ревью в PR...")
            val posted = prReviewService.submitReview(
                owner = config.owner,
                repo = config.repo,
                prNumber = config.prNumber,
                result = result
            )
            if (posted) {
                println("Ревью успешно опубликовано!")
            } else {
                println("WARNING: Не удалось опубликовать ревью")
            }
        }

        // Возвращаем exit code в зависимости от результата
        val exitCode = when (result.overallScore) {
            "REQUEST_CHANGES" -> {
                println("\nExit code: 1 (REQUEST_CHANGES)")
                1
            }
            "APPROVE" -> {
                println("\nExit code: 0 (APPROVE)")
                0
            }
            else -> {
                println("\nExit code: 0 (COMMENT)")
                0
            }
        }

        prReviewService.disconnect()
        httpClient.close()
        System.exit(exitCode)

    } catch (e: Exception) {
        println("\nERROR: ${e.message}")
        e.printStackTrace()
        prReviewService.disconnect()
        httpClient.close()
        System.exit(1)
    }
}

private data class ReviewConfig(
    val owner: String,
    val repo: String,
    val prNumber: Int,
    val useRag: Boolean = true,
    val postToPr: Boolean = true,
    val quickMode: Boolean = false,
    val securityMode: Boolean = false,
    val outputFile: String? = null,
    val projectRoot: String = System.getProperty("user.dir")
)

private fun parseArgs(args: Array<String>): ReviewConfig? {
    if (args.size < 3) return null

    val owner = args[0]
    val repo = args[1]
    val prNumber = args[2].toIntOrNull() ?: return null

    var useRag = true
    var postToPr = true
    var quickMode = false
    var securityMode = false
    var outputFile: String? = null
    var projectRoot = System.getenv("PROJECT_ROOT") ?: System.getProperty("user.dir")

    var i = 3
    while (i < args.size) {
        when (args[i]) {
            "--no-rag" -> useRag = false
            "--no-post" -> postToPr = false
            "--quick" -> quickMode = true
            "--security" -> securityMode = true
            "--output" -> {
                if (i + 1 < args.size) {
                    outputFile = args[++i]
                }
            }
            "--project-root" -> {
                if (i + 1 < args.size) {
                    projectRoot = args[++i]
                }
            }
        }
        i++
    }

    return ReviewConfig(
        owner = owner,
        repo = repo,
        prNumber = prNumber,
        useRag = useRag,
        postToPr = postToPr,
        quickMode = quickMode,
        securityMode = securityMode,
        outputFile = outputFile,
        projectRoot = projectRoot
    )
}

private fun printUsage() {
    println("""
        |
        |AI PR Reviewer - автоматическое ревью Pull Request с использованием RAG
        |
        |Использование:
        |  java -cp <classpath> org.example.prreview.PrReviewRunnerKt <owner> <repo> <pr_number> [options]
        |
        |Аргументы:
        |  owner      - владелец репозитория на GitHub
        |  repo       - название репозитория
        |  pr_number  - номер Pull Request
        |
        |Опции:
        |  --no-rag         - отключить RAG контекст (по умолчанию включён)
        |  --no-post        - не публиковать ревью в PR (только вывод в консоль)
        |  --quick          - быстрый режим (только критические проблемы)
        |  --security       - режим security review
        |  --output <file>  - записать результат в файл
        |  --project-root   - корень проекта для RAG (по умолчанию текущая директория)
        |
        |Переменные окружения:
        |  ANTHROPIC_API_KEY - API ключ Anthropic (обязательно)
        |  GITHUB_TOKEN      - GitHub токен (обязательно)
        |  PROJECT_ROOT      - корень проекта для RAG (опционально)
        |
        |Примеры:
        |  # Полное ревью с публикацией
        |  java -cp app.jar org.example.prreview.PrReviewRunnerKt owner repo 123
        |
        |  # Быстрое ревью без публикации
        |  java -cp app.jar org.example.prreview.PrReviewRunnerKt owner repo 123 --quick --no-post
        |
        |  # Security-focused ревью с записью в файл
        |  java -cp app.jar org.example.prreview.PrReviewRunnerKt owner repo 123 --security --output review.md
        |
    """.trimMargin())
}

private suspend fun createRagService(projectRoot: String, httpClient: HttpClient, json: Json): RagService? {
    return try {
        val embeddingClient = OllamaEmbeddingClient(httpClient, json)

        // Проверяем доступность Ollama
        if (!embeddingClient.isAvailable()) {
            println("WARNING: Ollama недоступна, RAG отключён")
            return null
        }

        val vectorStore = VectorStore()
        val chunkingService = ChunkingService()

        RagService(
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            chunkingService = chunkingService,
            projectRoot = File(projectRoot)
        )
    } catch (e: Exception) {
        println("WARNING: Не удалось создать RAG service: ${e.message}")
        null
    }
}

private suspend fun indexProjectFiles(ragService: RagService) {
    val result = ragService.indexProjectFiles(forceReindex = false) { status ->
        if (status.currentFile != null) {
            print("\r  Индексация: ${status.processedFiles}/${status.totalFiles} файлов, ${status.processedChunks} чанков")
        }
    }

    when (result) {
        is IndexingResult.Success -> {
            println("\r  Проиндексировано: ${result.filesProcessed} файлов, ${result.chunksCreated} чанков")
        }
        is IndexingResult.Error -> {
            println("\n  WARNING: Ошибка индексации: ${result.message}")
        }
        is IndexingResult.NotReady -> {
            println("\n  WARNING: RAG не готов: ${result.reason}")
        }
    }
}

private fun formatReviewOutput(result: PrReviewService.ReviewResult): String {
    return buildString {
        appendLine("# Code Review: ${result.prTitle}")
        appendLine("PR #${result.prNumber}")
        appendLine()

        appendLine("## Summary")
        appendLine(result.summary)
        appendLine()

        if (result.comments.isNotEmpty()) {
            appendLine("## Issues Found (${result.comments.size})")
            appendLine()

            val grouped = result.comments.groupBy { it.severity }

            grouped["critical"]?.let { critical ->
                appendLine("### Critical (${critical.size})")
                critical.forEach { c ->
                    appendLine("- **${c.file}${c.line?.let { ":$it" } ?: ""}**: ${c.message}")
                }
                appendLine()
            }

            grouped["warning"]?.let { warnings ->
                appendLine("### Warnings (${warnings.size})")
                warnings.forEach { c ->
                    appendLine("- **${c.file}${c.line?.let { ":$it" } ?: ""}**: ${c.message}")
                }
                appendLine()
            }

            grouped["suggestion"]?.let { suggestions ->
                appendLine("### Suggestions (${suggestions.size})")
                suggestions.forEach { c ->
                    appendLine("- **${c.file}${c.line?.let { ":$it" } ?: ""}**: ${c.message}")
                }
                appendLine()
            }

            grouped["nitpick"]?.let { nitpicks ->
                appendLine("### Nitpicks (${nitpicks.size})")
                nitpicks.forEach { c ->
                    appendLine("- **${c.file}${c.line?.let { ":$it" } ?: ""}**: ${c.message}")
                }
                appendLine()
            }
        }

        appendLine("## Overall Score: ${result.overallScore}")
        appendLine()
        appendLine("---")
        appendLine("*Generated by AI PR Reviewer with RAG*")
    }
}
