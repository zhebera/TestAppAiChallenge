package org.example.app

import org.example.app.commands.ChatState
import org.example.app.commands.CommandContext
import org.example.app.commands.CommandRegistry
import org.example.app.commands.CommandResult
import org.example.data.mcp.MultiMcpClient
import org.example.data.persistence.MemoryRepository
import org.example.data.rag.RagService
import org.example.data.rag.RerankerConfig
import org.example.data.rag.RerankerMethod
import org.example.data.repository.StreamResult
import org.example.domain.models.ChatHistory
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage
import org.example.presentation.ConsoleInput
import org.example.utils.SYSTEM_FORMAT_PROMPT

class ChatLoop(
    private val console: ConsoleInput,
    private val useCases: UseCases,
    private val memoryRepository: MemoryRepository,
    private val ragService: RagService? = null,
    private val multiMcpClient: MultiMcpClient? = null,
    private val classpath: String? = null
) {
    private val commandRegistry = CommandRegistry(ragService)

    suspend fun run() {
        printWelcome()

        val state = ChatState(
            currentSystemPrompt = SYSTEM_FORMAT_PROMPT,
            currentTemperature = null,
            currentMaxTokens = AppConfig.DEFAULT_MAX_TOKENS
        )

        val chatHistory = ChatHistory(
            maxStoredMessages = AppConfig.MAX_STORED_MESSAGES,
            compressEvery = AppConfig.COMPRESS_EVERY,
            memoryRepository = memoryRepository
        )

        // Всегда начинаем с чистой истории для экономии токенов
        chatHistory.initSession(createNew = true)
        println("Новая сессия начата (история очищена)")
        println()

        val context = CommandContext(
            console = console,
            chatHistory = chatHistory,
            memoryRepository = memoryRepository,
            useCases = useCases,
            state = state,
            multiMcpClient = multiMcpClient,
            classpath = classpath
        )

        while (true) {
            val line = console.readLine("user >> ") ?: run {
                println("\nВвод недоступен (EOF/ошибка). Выход из программы.")
                break
            }

            val text = line.trim()
            if (text.isEmpty()) continue

            when (val result = commandRegistry.tryExecute(text, context)) {
                CommandResult.Exit -> break
                CommandResult.Continue -> continue
                CommandResult.NotHandled -> processUserMessage(text, context)
            }
        }
    }

    private fun printWelcome() {
        println("LLM Chat. Введите 'exit' для выхода.\n")
        println("Команды:")
        println("  /new или /clear - начать новый диалог (очистить историю)")
        println("  /stats          - показать статистику истории")
        println("  /changePrompt   - сменить System Prompt")
        println("  /temperature    - изменить temperature (0.0 - 1.0)")
        println("  /maxTokens      - изменить max_tokens")
        println("  /memory         - работа с памятью сообщений")
        println("  /mcp            - управление MCP серверами (wikipedia, summarizer, ...)")
        println("  /rag            - RAG: поиск по локальной базе знаний")
        println()
    }

    private fun printSessionInfo(chatHistory: ChatHistory) {
        val stats = chatHistory.getStats()
        if (stats.currentMessageCount > 0 || stats.compressedMessageCount > 0) {
            println("Восстановлена предыдущая сессия:")
            println("  Сообщений в памяти: ${stats.currentMessageCount}")
            if (stats.compressedMessageCount > 0) {
                println("  Сжатых сообщений:   ${stats.compressedMessageCount}")
            }
            println("  (используйте /new для начала нового диалога)")
            println()
        }
    }

    private suspend fun processUserMessage(text: String, context: CommandContext) {
        val chatHistory = context.chatHistory
        val state = context.state

        // Сжатие предыдущей истории перед отправкой нового сообщения
        if (chatHistory.needsCompression() && useCases.compressHistory != null) {
            try {
                useCases.compressHistory.compressIfNeeded(chatHistory)
            } catch (_: Exception) {
                // Игнорируем ошибки сжатия
            }
        }

        // Показываем режим запроса
        val mode = buildString {
            if (state.ragEnabled) append("[RAG]") else append("[без RAG]")
            if (multiMcpClient?.isConnected == true) {
                append(" [MCP: ${multiMcpClient.connectedServers.joinToString(", ")}]")
            }
        }
        println(mode)

        // RAG: добавляем контекст из базы знаний если включено
        val messageWithRag = if (state.ragEnabled && ragService != null) {
            try {
                val useReranking = state.rerankerEnabled && ragService.hasReranker()

                val (formattedContext, resultsInfo) = if (useReranking) {
                    // Поиск с реранкингом
                    val config = RerankerConfig(
                        method = when (state.rerankerMethod.lowercase()) {
                            "cross" -> RerankerMethod.CROSS_ENCODER
                            "llm" -> RerankerMethod.LLM_SCORING
                            "keyword" -> RerankerMethod.KEYWORD_HYBRID
                            else -> RerankerMethod.CROSS_ENCODER
                        },
                        threshold = state.rerankerThreshold,
                        useKeywordBoost = true,
                        maxCandidates = 10
                    )
                    val result = ragService.searchWithReranking(
                        query = text,
                        topK = 3,
                        initialTopK = 8,
                        minSimilarity = 0.25f,
                        rerankerConfig = config
                    )

                    // Формируем информацию о результатах
                    val info = buildString {
                        if (result.finalResults.isNotEmpty()) {
                            appendLine("Найдено ${result.finalResults.size} релевантных фрагментов (с реранкингом):")
                            result.rerankedResults
                                .filter { !it.wasFiltered }
                                .take(3)
                                .forEachIndexed { i, reranked ->
                                    val newScore = "%.0f%%".format(reranked.rerankedScore * 100)
                                    val oldScore = "%.0f%%".format(reranked.originalScore * 100)
                                    appendLine("  ${i + 1}. ${reranked.original.chunk.sourceFile} ($newScore, было $oldScore)")
                                }
                            if (result.filteredCount > 0) {
                                appendLine("  (отфильтровано ${result.filteredCount} нерелевантных)")
                            }
                        } else {
                            appendLine("Релевантных фрагментов не найдено (после фильтрации)")
                        }
                    }
                    Pair(result.formattedContext, info)
                } else {
                    // Обычный поиск без реранкинга
                    val ragContext = ragService.search(text, topK = 3, minSimilarity = 0.35f)
                    val info = buildString {
                        if (ragContext.results.isNotEmpty()) {
                            appendLine("Найдено ${ragContext.results.size} релевантных фрагментов:")
                            ragContext.results.forEachIndexed { i, result ->
                                val similarity = "%.0f%%".format(result.similarity * 100)
                                appendLine("  ${i + 1}. ${result.chunk.sourceFile} ($similarity)")
                            }
                        } else {
                            appendLine("Релевантных фрагментов не найдено")
                        }
                    }
                    Pair(ragContext.formattedContext, info)
                }

                println(resultsInfo)

                if (formattedContext.isNotEmpty()) {
                    val enrichedMessage = "$formattedContext\n\nВопрос пользователя: $text"

                    // Debug: показываем полный запрос
                    if (state.ragDebug) {
                        println("─".repeat(60))
                        println("[DEBUG] Полный запрос с RAG-контекстом:")
                        println("─".repeat(60))
                        println(enrichedMessage)
                        println("─".repeat(60))
                        println()
                    }

                    enrichedMessage
                } else {
                    text
                }
            } catch (e: Exception) {
                println("Ошибка поиска: ${e.message}")
                println()
                text
            }
        } else {
            // Debug: показываем что отправляется без RAG
            if (state.ragDebug) {
                println("─".repeat(60))
                println("[DEBUG] Запрос без RAG:")
                println("─".repeat(60))
                println(text)
                println("─".repeat(60))
                println()
            }
            text
        }

        chatHistory.addMessage(ChatRole.USER, messageWithRag)

        try {
            val conversationWithSystem = buildConversation(chatHistory, state.currentSystemPrompt)
            val answer = sendAndCollectResponse(conversationWithSystem, state)

            answer?.let {
                ResponsePrinter.printResponse(it)
                chatHistory.addMessage(ChatRole.ASSISTANT, it.message)
                ResponsePrinter.printTokenStats(it, chatHistory)
            }
        } catch (t: Throwable) {
            chatHistory.removeLastMessage()
            println()
            println("Ошибка при запросе: ${t.message}")
            println("(Сообщение не сохранено в историю)")
            println()
        }
    }

    private fun buildConversation(chatHistory: ChatHistory, systemPrompt: String): List<LlmMessage> {
        return if (systemPrompt.isNotBlank()) {
            listOf(LlmMessage(role = ChatRole.SYSTEM, content = systemPrompt)) + chatHistory.messages
        } else {
            chatHistory.messages
        }
    }

    private suspend fun sendAndCollectResponse(
        conversation: List<LlmMessage>,
        state: ChatState
    ): LlmAnswer? {
        var finalAnswer: LlmAnswer? = null

        print("...")
        System.out.flush()

        useCases.sendMessage.stream(
            conversation,
            state.currentTemperature,
            state.currentMaxTokens,
        ).collect { result ->
            when (result) {
                is StreamResult.TextChunk -> { /* Накапливаем молча */ }
                is StreamResult.Complete -> finalAnswer = result.answer
            }
        }

        print("\r")
        return finalAnswer
    }
}
