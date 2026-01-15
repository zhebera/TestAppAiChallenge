package org.example.team

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.app.AppConfig
import org.example.data.api.AnthropicClient
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.McpStdioTransport
import org.example.data.network.StreamEvent
import org.example.data.persistence.DatabaseConfig
import org.example.data.rag.*
import org.example.mcp.server.tasks.TaskDataManager
import java.io.File

/**
 * Консольное приложение командного ассистента.
 *
 * Возможности:
 * - Знание проекта через RAG (поиск по коду и документации)
 * - Управление задачами через MCP (Tasks server)
 * - Информация о репозитории через MCP (GitHub server)
 * - Рекомендации по приоритетам
 * - Intent classification для оптимальной загрузки контекста
 * - Создание задач из естественного языка
 *
 * Команды:
 * - /status - показать статус проекта
 * - /tasks [priority] - список задач (опционально по приоритету)
 * - /create - создать задачу (интерактивно)
 * - /nlcreate <описание> - создать задачу из текста
 * - /recommend - получить рекомендации по приоритетам
 * - /team - показать загрузку команды
 * - /help - справка по командам
 * - exit - выход
 */
fun main() = runBlocking {
    println()
    println("╔═══════════════════════════════════════════════════════════════════╗")
    println("║            Team Assistant - Командный ассистент                   ║")
    println("╠═══════════════════════════════════════════════════════════════════╣")
    println("║  Я помогу вам:                                                    ║")
    println("║  - Управлять задачами проекта                                     ║")
    println("║  - Отвечать на вопросы о коде и архитектуре                       ║")
    println("║  - Давать рекомендации по приоритетам                             ║")
    println("║                                                                   ║")
    println("║  Команды: /status, /tasks, /create, /nlcreate, /recommend, /team  ║")
    println("║  Для выхода введите: exit                                         ║")
    println("╚═══════════════════════════════════════════════════════════════════╝")
    println()

    // Инициализация
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    DatabaseConfig.init()
    val httpClient = AppConfig.buildHttpClient(json)

    // Проверка API ключа
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    if (anthropicKey.isNullOrBlank()) {
        println("Ошибка: Для работы требуется ANTHROPIC_API_KEY")
        println("Установите: export ANTHROPIC_API_KEY=ваш_ключ")
        return@runBlocking
    }

    // LLM клиенты
    val sonnetClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey,
        model = AppConfig.CLAUDE_SONNET_MODEL
    )

    val haikuClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey,
        model = AppConfig.CLAUDE_HAIKU_MODEL
    )

    // Intent Classifier и NL Task Parser
    val intentClassifier = IntentClassifier(haikuClient, json)
    val taskParser = NaturalLanguageTaskParser(haikuClient, json)

    // RAG инициализация
    print("Инициализация RAG... ")
    System.out.flush()

    val embeddingClient = OllamaEmbeddingClient(httpClient, json)
    val vectorStore = VectorStore()
    val chunkingService = ChunkingService()
    val rerankerService = RerankerService(httpClient, json, embeddingClient)

    val projectRoot = File(".")
    val ragService = RagService(
        embeddingClient = embeddingClient,
        vectorStore = vectorStore,
        chunkingService = chunkingService,
        ragDirectory = File("rag_files"),
        projectRoot = projectRoot,
        rerankerService = rerankerService
    )

    var ragReady = false
    when (val readiness = ragService.checkReadiness()) {
        is ReadinessResult.Ready -> {
            val stats = ragService.getIndexStats()
            if (stats.totalChunks == 0L) {
                print("индексация... ")
                System.out.flush()
                ragService.indexDocuments(forceReindex = false)
                ragService.indexProjectFiles(forceReindex = false)
                val newStats = ragService.getIndexStats()
                println("готово! (${newStats.indexedFiles.size} файлов)")
            } else {
                println("готово (${stats.indexedFiles.size} файлов)")
            }
            ragReady = true
        }
        is ReadinessResult.OllamaNotRunning -> {
            println("пропущено (Ollama не запущена)")
        }
        is ReadinessResult.ModelNotFound -> {
            println("пропущено (модель не установлена)")
        }
    }

    // Инициализация TaskDataManager (напрямую, без MCP subprocess)
    print("Инициализация задач... ")
    System.out.flush()
    val taskDataManager = TaskDataManager()
    val tasksCount = taskDataManager.getAllTasks().size
    println("готово ($tasksCount задач)")

    // MCP GitHub подключение (опционально)
    print("Подключение GitHub MCP... ")
    System.out.flush()

    val classpath = System.getProperty("java.class.path")
    val githubMcpClient: McpClient? = try {
        val githubConfig = McpClientFactory.createGitHubExtendedConfig(classpath)
        val transport = McpStdioTransport(githubConfig, json)
        val client = McpClient(transport, json)
        client.connect()
        println("успешно")
        client
    } catch (e: Exception) {
        println("пропущено")
        null
    }

    // Создаём сервис с TaskDataManager напрямую
    val assistantService = TeamAssistantService(
        ragService = ragService,
        llmClient = sonnetClient,
        taskDataManager = taskDataManager,
        githubMcpClient = githubMcpClient,
        json = json,
        intentClassifier = intentClassifier,
        taskParser = taskParser
    )

    println()

    // Проактивные уведомления при запуске
    showStartupNotifications(assistantService)

    println("Готов к работе! Задайте вопрос или введите команду.")
    println()

    // Главный цикл
    while (true) {
        print("Вы: ")
        System.out.flush()

        val input = readlnOrNull()?.trim() ?: break

        when {
            input.isEmpty() -> continue

            input == "exit" || input == "quit" -> {
                println()
                println("До свидания!")
                githubMcpClient?.disconnect()
                break
            }

            input == "/help" -> {
                printHelp()
                continue
            }

            input == "/status" -> {
                println()
                println(assistantService.getProjectStatus())
                println()
                continue
            }

            input.startsWith("/tasks") -> {
                val priority = input.removePrefix("/tasks").trim().ifBlank { null }
                println()
                if (priority != null) {
                    println(assistantService.getTasksByPriority(priority))
                } else {
                    val result = assistantService.executeTaskAction(TaskAction.ListTasks())
                    when (result) {
                        is TaskActionResult.Success -> println(result.data)
                        is TaskActionResult.Error -> println("Ошибка: ${result.message}")
                    }
                }
                println()
                continue
            }

            input == "/recommend" -> {
                println()
                println(assistantService.getRecommendations())
                println()
                continue
            }

            input == "/team" -> {
                println()
                val result = assistantService.executeTaskAction(TaskAction.GetTeamWorkload)
                when (result) {
                    is TaskActionResult.Success -> println(result.data)
                    is TaskActionResult.Error -> println("Ошибка: ${result.message}")
                }
                println()
                continue
            }

            input == "/create" -> {
                handleCreateTask(assistantService)
                continue
            }

            input.startsWith("/nlcreate ") -> {
                val description = input.removePrefix("/nlcreate ").trim()
                if (description.isBlank()) {
                    println("Использование: /nlcreate <описание задачи>")
                    println()
                    continue
                }
                handleNLCreateTask(assistantService, taskParser, description)
                continue
            }

            input.startsWith("/delete ") -> {
                val taskId = input.removePrefix("/delete ").trim()
                handleDeleteTask(assistantService, taskId)
                continue
            }

            input == "/reindex" -> {
                handleReindex(ragService, ragReady)
                continue
            }

            input == "/rag" -> {
                handleRagStatus(ragService, ragReady)
                continue
            }

            input.startsWith("/") -> {
                println("Неизвестная команда. Введите /help для справки.")
                println()
                continue
            }

            else -> {
                // Обычный вопрос - отправляем в LLM
                println()
                print("Ассистент: ")
                System.out.flush()

                try {
                    val streamFlow = assistantService.processMessageStream(input)
                    streamFlow.collect { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                print(event.text)
                                System.out.flush()
                            }
                            is StreamEvent.Complete -> {}
                        }
                    }
                    println()
                    println()
                } catch (e: Exception) {
                    println()
                    println("Ошибка: ${e.message}")
                    println()
                }
            }
        }
    }
}

/**
 * Показывает проактивные уведомления при запуске.
 */
private suspend fun showStartupNotifications(service: TeamAssistantService) {
    println("=== Уведомления ===")
    println()

    try {
        // Получаем статус проекта
        val statusResult = service.executeTaskAction(TaskAction.GetStatus)
        if (statusResult is TaskActionResult.Success) {
            val statusText = statusResult.data

            // Парсим ключевые метрики из текста
            val criticalCount = extractNumber(statusText, "CRITICAL:")
            val highCount = extractNumber(statusText, "HIGH:")

            if (criticalCount > 0) {
                println("⚠️  ВНИМАНИЕ: $criticalCount CRITICAL задач требуют немедленного внимания!")
            }
            if (highCount > 0) {
                println("📋 HIGH приоритет: $highCount задач")
            }

            // Прогресс спринта
            if (statusText.contains("Активный спринт:")) {
                val progressMatch = Regex("""Прогресс: (\d+)/(\d+)""").find(statusText)
                if (progressMatch != null) {
                    val done = progressMatch.groupValues[1].toInt()
                    val total = progressMatch.groupValues[2].toInt()
                    val percent = if (total > 0) done * 100 / total else 0
                    println("🏃 Спринт: $done/$total ($percent%)")
                }
            }
        }

        // Проверяем дедлайны (задачи с due_date в ближайшие 2 дня)
        val tasksResult = service.executeTaskAction(TaskAction.ListTasks())
        if (tasksResult is TaskActionResult.Success) {
            val tasksText = tasksResult.data
            if (tasksText.contains("due") || tasksText.contains("Срок")) {
                println("📅 Проверьте дедлайны в списке задач")
            }
        }

        println()
    } catch (e: Exception) {
        // Игнорируем ошибки уведомлений
    }
}

private fun extractNumber(text: String, prefix: String): Int {
    val regex = Regex("""$prefix\s*(\d+)""")
    return regex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

private fun printHelp() {
    println()
    println("""
╔═══════════════════════════════════════════════════════════════════╗
║                         Справка                                   ║
╠═══════════════════════════════════════════════════════════════════╣
║  Команды задач:                                                   ║
║    /status      - показать статус проекта и спринта               ║
║    /tasks       - список всех задач                               ║
║    /tasks high  - задачи с приоритетом high                       ║
║    /recommend   - получить рекомендации по приоритетам            ║
║    /team        - показать загрузку команды                       ║
║    /create      - создать новую задачу (интерактивно)             ║
║    /nlcreate <текст> - создать задачу из описания                 ║
║    /delete <id> - удалить задачу (с подтверждением)               ║
║                                                                   ║
║  Команды RAG (знание кода):                                       ║
║    /rag         - статус RAG системы и индекса                    ║
║    /reindex     - переиндексировать все файлы проекта             ║
║                                                                   ║
║  Общие:                                                           ║
║    /help        - эта справка                                     ║
║    exit         - выход                                           ║
║                                                                   ║
║  Примеры вопросов:                                                ║
║    "Покажи задачи с приоритетом high и предложи что делать"       ║
║    "Какая архитектура у этого проекта?"                           ║
║    "Как работает RAG система?"                                    ║
║    "Что можно улучшить в TeamAssistantService?"                   ║
╚═══════════════════════════════════════════════════════════════════╝
    """.trimIndent())
    println()
}

private suspend fun handleCreateTask(service: TeamAssistantService) {
    println()
    println("=== Создание задачи ===")
    println()

    print("Заголовок: ")
    System.out.flush()
    val title = readlnOrNull()?.trim()
    if (title.isNullOrBlank()) {
        println("Отменено")
        println()
        return
    }

    print("Описание: ")
    System.out.flush()
    val description = readlnOrNull()?.trim() ?: ""

    print("Тип (feature/bug/tech_debt/spike/improvement) [feature]: ")
    System.out.flush()
    val type = readlnOrNull()?.trim()?.ifBlank { "feature" } ?: "feature"

    print("Приоритет (low/medium/high/critical) [medium]: ")
    System.out.flush()
    val priority = readlnOrNull()?.trim()?.ifBlank { "medium" } ?: "medium"

    // Подтверждение для critical
    if (priority == "critical") {
        print("⚠️  Вы уверены что задача CRITICAL? (y/n): ")
        System.out.flush()
        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm != "y" && confirm != "yes" && confirm != "да") {
            println("Отменено")
            println()
            return
        }
    }

    print("Исполнитель ID (пусто для пропуска): ")
    System.out.flush()
    val assigneeId = readlnOrNull()?.trim()?.ifBlank { null }

    println()
    println("Создаю задачу...")
    println()

    val result = service.createTask(
        title = title,
        description = description.ifBlank { title },
        priority = priority,
        type = type,
        assigneeId = assigneeId
    )

    println(result)
    println()
}

private suspend fun handleNLCreateTask(
    service: TeamAssistantService,
    parser: NaturalLanguageTaskParser,
    description: String
) {
    println()
    println("Анализирую описание...")
    println()

    try {
        val parsedData = parser.parse(description)

        // Показываем превью
        println(parser.formatForConfirmation(parsedData))
        println()

        // Подтверждение для critical
        if (parsedData.priority == "critical") {
            print("⚠️  Задача будет создана с приоритетом CRITICAL. Подтвердить? (y/n): ")
        } else {
            print("Создать задачу? (y/n): ")
        }
        System.out.flush()

        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm != "y" && confirm != "yes" && confirm != "да") {
            println("Отменено")
            println()
            return
        }

        println()
        println("Создаю задачу...")

        val result = service.createTask(
            title = parsedData.title,
            description = parsedData.description,
            priority = parsedData.priority,
            type = parsedData.type
        )

        println(result)
        println()
    } catch (e: Exception) {
        println("Ошибка: ${e.message}")
        println()
    }
}

private suspend fun handleDeleteTask(service: TeamAssistantService, taskId: String) {
    if (taskId.isBlank()) {
        println("Использование: /delete <task_id>")
        println()
        return
    }

    println()

    // Получаем информацию о задаче
    val taskResult = service.executeTaskAction(TaskAction.GetTask(taskId))
    when (taskResult) {
        is TaskActionResult.Success -> {
            println("Задача для удаления:")
            println(taskResult.data)
            println()
        }
        is TaskActionResult.Error -> {
            println("Задача не найдена: ${taskResult.message}")
            println()
            return
        }
    }

    // Запрашиваем подтверждение
    print("⚠️  Вы уверены что хотите УДАЛИТЬ эту задачу? (yes/no): ")
    System.out.flush()

    val confirm = readlnOrNull()?.trim()?.lowercase()
    if (confirm != "yes" && confirm != "да") {
        println("Отменено")
        println()
        return
    }

    // Удаляем
    val deleteResult = service.executeTaskAction(TaskAction.DeleteTask(taskId))
    when (deleteResult) {
        is TaskActionResult.Success -> println("Задача удалена")
        is TaskActionResult.Error -> println("Ошибка: ${deleteResult.message}")
    }
    println()
}

private suspend fun handleReindex(ragService: RagService, ragReady: Boolean) {
    println()
    println("=== Переиндексация проекта ===")
    println()

    // Проверяем готовность RAG
    val readiness = ragService.checkReadiness()
    when (readiness) {
        is ReadinessResult.OllamaNotRunning -> {
            println("❌ Ollama не запущена!")
            println("   Запустите: ollama serve")
            println()
            return
        }
        is ReadinessResult.ModelNotFound -> {
            println("❌ Модель ${readiness.model} не найдена!")
            println("   Установите: ollama pull mxbai-embed-large")
            println()
            return
        }
        is ReadinessResult.Ready -> {
            // OK, продолжаем
        }
    }

    var totalFiles = 0
    var totalChunks = 0

    // СНАЧАЛА индексируем документы из rag_files (forceReindex=true очистит всё)
    print("1. Индексация документов из rag_files... ")
    System.out.flush()

    val docsResult = ragService.indexDocuments(forceReindex = true)
    when (docsResult) {
        is IndexingResult.Success -> {
            println("OK (${docsResult.filesProcessed} файлов, ${docsResult.chunksCreated} чанков)")
            totalFiles += docsResult.filesProcessed
            totalChunks += docsResult.chunksCreated
        }
        is IndexingResult.Error -> {
            println("пропущено (${docsResult.message})")
        }
        is IndexingResult.NotReady -> {
            println("пропущено")
        }
    }

    // ПОТОМ индексируем файлы проекта (forceReindex=true НЕ очистит всё, только файлы проекта)
    print("2. Индексация файлов проекта (.kt, .md, .kts)... ")
    System.out.flush()

    val result = ragService.indexProjectFiles(forceReindex = true) { status ->
        // Прогресс
    }

    when (result) {
        is IndexingResult.Success -> {
            println("OK")
            println("   Файлов обработано: ${result.filesProcessed}")
            println("   Чанков создано: ${result.chunksCreated}")
            totalFiles += result.filesProcessed
            totalChunks += result.chunksCreated
        }
        is IndexingResult.Error -> {
            println("ОШИБКА: ${result.message}")
        }
        is IndexingResult.NotReady -> {
            println("НЕ ГОТОВО")
        }
    }

    println()
    println("✅ Переиндексация завершена!")
    println("   Всего: $totalFiles файлов, $totalChunks чанков")
    println("   Теперь ассистент знает о всех файлах проекта.")
    println()
}

private suspend fun handleRagStatus(ragService: RagService, ragReady: Boolean) {
    println()
    println("=== Статус RAG системы ===")
    println()

    // Проверяем Ollama
    val readiness = ragService.checkReadiness()
    when (readiness) {
        is ReadinessResult.Ready -> {
            println("Ollama: ✅ запущена")
            println("Модель: ✅ mxbai-embed-large доступна")
        }
        is ReadinessResult.OllamaNotRunning -> {
            println("Ollama: ❌ не запущена")
            println("   Запустите: ollama serve")
        }
        is ReadinessResult.ModelNotFound -> {
            println("Ollama: ✅ запущена")
            println("Модель: ❌ ${readiness.model} не найдена")
            println("   Установите: ollama pull mxbai-embed-large")
        }
    }

    // Статистика индекса
    val stats = ragService.getIndexStats()
    println()
    println("Индекс:")
    println("  Файлов проиндексировано: ${stats.indexedFiles.size}")
    println("  Всего чанков: ${stats.totalChunks}")

    if (stats.indexedFiles.isNotEmpty()) {
        println()
        println("Примеры проиндексированных файлов:")
        stats.indexedFiles
            .filter { it.endsWith(".kt") }
            .take(5)
            .forEach { file ->
                println("  • $file")
            }

        val teamFiles = stats.indexedFiles.filter { it.contains("team") }
        if (teamFiles.isNotEmpty()) {
            println()
            println("Файлы Team Assistant:")
            teamFiles.forEach { file ->
                println("  • $file")
            }
        }
    }

    println()
}
