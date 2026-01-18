package org.example.fullcycle

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.rag.ChunkingService
import org.example.data.rag.OllamaEmbeddingClient
import org.example.data.rag.RagService
import org.example.data.rag.VectorStore
import java.io.File

/**
 * Full-Cycle Pipeline Runner
 *
 * Запуск из командной строки:
 *   ./gradlew runFullCycle --args="'описание задачи' [опции]"
 *
 * Опции:
 *   --auto          Автоматически подтверждать план (без интерактива)
 *   --no-merge      Не мерджить автоматически, оставить PR открытым
 *   --no-ci         Не ждать CI
 *   --output FILE   Сохранить отчёт в файл
 *
 * Переменные окружения:
 *   ANTHROPIC_API_KEY     - API ключ Anthropic (обязательно)
 *   GITHUB_TOKEN          - GitHub токен (обязательно)
 *   PROJECT_ROOT          - Корень проекта для RAG (опционально)
 */
fun main(args: Array<String>) = runBlocking {
    println("Full-Cycle Pipeline Runner")
    println("=".repeat(50))

    // Парсим аргументы
    val parsedArgs = parseArgs(args)

    if (parsedArgs.taskDescription.isBlank()) {
        printUsage()
        return@runBlocking
    }

    // Проверяем переменные окружения
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    if (anthropicKey.isNullOrBlank()) {
        printError("ANTHROPIC_API_KEY не установлен")
        return@runBlocking
    }

    val githubToken = System.getenv("GITHUB_TOKEN")
        ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")

    if (githubToken.isNullOrBlank()) {
        printError("GITHUB_TOKEN не установлен")
        return@runBlocking
    }

    println("Задача: ${parsedArgs.taskDescription}")
    println("Режим: ${if (parsedArgs.autoConfirm) "автоматический" else "интерактивный"}")
    println()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Создаём HTTP клиент
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 150_000
            connectTimeoutMillis = 100_000
        }
    }

    // Создаём LLM клиент
    val llmClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey,
        model = "claude-sonnet-4-20250514"
    )

    // Создаём RAG сервис (опционально)
    val ragService = createRagService(
        projectRoot = System.getenv("PROJECT_ROOT") ?: System.getProperty("user.dir"),
        httpClient = httpClient,
        json = json
    )

    // Создаём конфигурацию
    val config = PipelineConfig(
        maxReviewIterations = 10,
        maxCIRetries = 5,
        autoMerge = !parsedArgs.noMerge,
        requireCIPass = !parsedArgs.noCI
    )

    // Создаём сервис пайплайна
    val pipelineService = FullCyclePipelineService(
        llmClient = llmClient,
        ragService = ragService,
        githubToken = githubToken,
        config = config
    )

    // Настраиваем логирование
    pipelineService.onProgress = { message ->
        println(message)
    }

    // Запускаем пайплайн
    try {
        val report = pipelineService.executeFullCycle(
            taskDescription = parsedArgs.taskDescription,
            confirmPlan = { plan ->
                if (parsedArgs.autoConfirm) {
                    println("\n[AUTO] План подтверждён автоматически")
                    true
                } else {
                    print("\nНачать выполнение? (y/n): ")
                    System.out.flush()
                    val response = readlnOrNull()?.trim()?.lowercase()
                    response == "y" || response == "yes" || response == "да"
                }
            }
        )

        // Сохраняем отчёт если указан файл
        if (parsedArgs.outputFile != null) {
            saveReport(report, parsedArgs.outputFile)
            println("\nОтчёт сохранён: ${parsedArgs.outputFile}")
        }

        // Код выхода
        if (!report.success) {
            System.exit(1)
        }

    } catch (e: Exception) {
        printError("Критическая ошибка: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    } finally {
        httpClient.close()
    }
}

private suspend fun createRagService(projectRoot: String, httpClient: HttpClient, json: Json): RagService? {
    return try {
        val embeddingClient = OllamaEmbeddingClient(httpClient, json)

        // Проверяем доступность Ollama
        if (!embeddingClient.isAvailable()) {
            println("RAG: Ollama недоступна, RAG отключён")
            return null
        }

        val vectorStore = VectorStore()
        val chunkingService = ChunkingService()

        val ragService = RagService(
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            chunkingService = chunkingService,
            projectRoot = File(projectRoot)
        )

        println("RAG: индексируем проект...")
        ragService.indexProjectFiles()
        println("RAG: готов")

        ragService
    } catch (e: Exception) {
        println("RAG: недоступен (${e.message})")
        null
    }
}

private data class ParsedArgs(
    val taskDescription: String = "",
    val autoConfirm: Boolean = false,
    val noMerge: Boolean = false,
    val noCI: Boolean = false,
    val outputFile: String? = null
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var taskDescription = ""
    var autoConfirm = false
    var noMerge = false
    var noCI = false
    var outputFile: String? = null

    var i = 0
    while (i < args.size) {
        when {
            args[i] == "--auto" -> autoConfirm = true
            args[i] == "--no-merge" -> noMerge = true
            args[i] == "--no-ci" -> noCI = true
            args[i] == "--output" && i + 1 < args.size -> {
                outputFile = args[++i]
            }
            args[i] == "--help" || args[i] == "-h" -> {
                printUsage()
                System.exit(0)
            }
            !args[i].startsWith("-") -> {
                // Собираем описание задачи из всех не-флаговых аргументов
                val parts = mutableListOf<String>()
                while (i < args.size && !args[i].startsWith("-")) {
                    parts.add(args[i])
                    i++
                }
                taskDescription = parts.joinToString(" ").removeSurrounding("'").removeSurrounding("\"")
                continue
            }
        }
        i++
    }

    return ParsedArgs(
        taskDescription = taskDescription,
        autoConfirm = autoConfirm,
        noMerge = noMerge,
        noCI = noCI,
        outputFile = outputFile
    )
}

private fun saveReport(report: PipelineReport, filename: String) {
    val content = buildString {
        appendLine("# Full-Cycle Pipeline Report")
        appendLine()
        appendLine("## Результат")
        appendLine("- Статус: ${if (report.success) "✅ Успешно" else "❌ Ошибка"}")
        report.prNumber?.let { appendLine("- PR: #$it") }
        report.prUrl?.let { appendLine("- URL: $it") }
        report.branchName?.let { appendLine("- Ветка: $it") }
        appendLine()

        if (report.changedFiles.isNotEmpty()) {
            appendLine("## Изменённые файлы")
            report.changedFiles.forEach { file ->
                val status = if (file.isNew) " (новый)" else ""
                appendLine("- ${file.path} (+${file.linesAdded}, -${file.linesRemoved})$status")
            }
            appendLine()
        }

        appendLine("## Статистика")
        appendLine("- Итераций review: ${report.reviewIterations}")
        appendLine("- Запусков CI: ${report.ciRuns}")
        appendLine("- Время выполнения: ${report.totalDuration / 1000} сек")
        appendLine()

        if (report.errors.isNotEmpty()) {
            appendLine("## Ошибки")
            report.errors.forEach { error ->
                appendLine("- $error")
            }
            appendLine()
        }

        appendLine("---")
        appendLine("*Сгенерировано Full-Cycle Pipeline*")
    }

    File(filename).writeText(content)
}

private fun printUsage() {
    println("""

        Использование:
          ./gradlew runFullCycle --args="'описание задачи' [опции]"

        Примеры:
          ./gradlew runFullCycle --args="'добавить кнопку выхода в настройках'"
          ./gradlew runFullCycle --args="'исправить баг' --auto --output report.md"

        Опции:
          --auto          Автоматически подтверждать план (для CI/CD)
          --no-merge      Не мерджить автоматически, оставить PR открытым
          --no-ci         Не ждать CI (мерджить сразу после review)
          --output FILE   Сохранить отчёт в файл
          --help, -h      Показать эту справку

        Переменные окружения:
          ANTHROPIC_API_KEY     API ключ Anthropic (обязательно)
          GITHUB_TOKEN          GitHub токен с правами push/merge (обязательно)
          PROJECT_ROOT          Корень проекта для RAG (опционально)

    """.trimIndent())
}

private fun printError(message: String) {
    System.err.println("\u001B[31m✗ $message\u001B[0m")
}
