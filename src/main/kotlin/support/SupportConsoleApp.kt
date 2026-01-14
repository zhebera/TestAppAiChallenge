package org.example.support

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
import org.example.support.api.SupportChatRequest
import org.example.support.service.SupportService
import java.io.File

/**
 * Консольное приложение для интерактивного чата с поддержкой.
 * Автоматически использует RAG и CRM для контекста.
 * Посимвольный вывод ответов (streaming).
 */
fun main() = runBlocking {
    println()
    println("╔═══════════════════════════════════════════════════════════════╗")
    println("║         Служба поддержки - LLM Chat                           ║")
    println("╠═══════════════════════════════════════════════════════════════╣")
    println("║  Здравствуйте! Я помощник службы поддержки.                   ║")
    println("║  Задавайте вопросы о приложении, и я постараюсь помочь.       ║")
    println("║                                                               ║")
    println("║  Для выхода введите: exit                                     ║")
    println("╚═══════════════════════════════════════════════════════════════╝")
    println()

    // Инициализация (тихо, без лишних сообщений)
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
        println("Ошибка: Для работы требуется API-ключ.")
        println("Установите его командой: export ANTHROPIC_API_KEY=ваш_ключ")
        return@runBlocking
    }

    val anthropicClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey,
        model = AppConfig.CLAUDE_SONNET_MODEL
    )

    // RAG (тихая инициализация)
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

    // Проверяем RAG и индексируем всё: FAQ + код проекта
    when (val readiness = ragService.checkReadiness()) {
        is ReadinessResult.Ready -> {
            val stats = ragService.getIndexStats()

            if (stats.totalChunks == 0L) {
                // Индексируем FAQ документацию
                print("Загрузка документации... ")
                System.out.flush()
                ragService.indexDocuments(forceReindex = false)
                print("готово! ")

                // Индексируем файлы проекта
                print("Загрузка кода проекта... ")
                System.out.flush()
                ragService.indexProjectFiles(forceReindex = false)
                val newStats = ragService.getIndexStats()
                println("готово! (${newStats.indexedFiles.size} файлов)")
            } else {
                println("База знаний загружена (${stats.indexedFiles.size} файлов)")
            }
        }
        is ReadinessResult.OllamaNotRunning -> {
            println()
            println("⚠ ВНИМАНИЕ: Ollama не запущена!")
            println("  Запустите в другом терминале: ollama serve")
            println("  Без этого ассистент будет отвечать неточно.")
            println()
        }
        is ReadinessResult.ModelNotFound -> {
            println()
            println("⚠ ВНИМАНИЕ: Модель не установлена!")
            println("  Выполните: ollama pull mxbai-embed-large")
            println()
        }
    }

    // CRM MCP (тихое подключение)
    val crmMcpClient: McpClient? = try {
        val classpath = System.getProperty("java.class.path")
        val crmConfig = McpClientFactory.createCrmConfig(classpath)
        val transport = McpStdioTransport(crmConfig, json)
        val client = McpClient(transport, json)
        client.connect()
        client
    } catch (e: Exception) {
        null
    }

    // Support Service
    val supportService = SupportService(
        ragService = ragService,
        llmClient = anthropicClient,
        crmMcpClient = crmMcpClient,
        json = json
    )

    // Контекст сессии
    var currentUserId: String? = null
    var currentTicketId: String? = null

    // Главный цикл
    while (true) {
        print("Вы: ")
        System.out.flush()

        val input = readlnOrNull()?.trim() ?: break

        when {
            input.isEmpty() -> continue

            input == "exit" || input == "quit" -> {
                println()
                println("Спасибо за обращение! До свидания!")
                crmMcpClient?.disconnect()
                break
            }

            input.startsWith("/user ") -> {
                currentUserId = input.removePrefix("/user ").trim()
                println("Контекст установлен: пользователь $currentUserId")
                println()
                continue
            }

            input.startsWith("/ticket ") -> {
                currentTicketId = input.removePrefix("/ticket ").trim()
                println("Контекст установлен: тикет $currentTicketId")
                println()
                continue
            }
        }

        // Отправляем вопрос
        println()
        print("Поддержка: ")
        System.out.flush()

        try {
            val (streamFlow, sources) = supportService.processMessageStream(
                SupportChatRequest(
                    userId = currentUserId,
                    ticketId = currentTicketId,
                    message = input
                )
            )

            // Выводим ответ посимвольно
            streamFlow.collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        print(event.text)
                        System.out.flush()
                    }
                    is StreamEvent.Complete -> {
                        // Ответ завершён
                    }
                }
            }

            println()
            println()

        } catch (e: Exception) {
            println()
            println("Извините, произошла ошибка. Пожалуйста, попробуйте позже.")
            println()
        }
    }
}
