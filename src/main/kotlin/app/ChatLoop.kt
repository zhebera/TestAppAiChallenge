package org.example.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.commands.ChatState
import org.example.app.commands.CommandContext
import org.example.app.commands.CommandRegistry
import org.example.app.commands.CommandResult
import org.example.data.analysis.DataAnalysisService
import org.example.data.api.OllamaClient
import org.example.data.api.OllamaFunctionDto
import org.example.data.api.OllamaToolDto
import org.example.data.dto.LlmRequest
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
import org.example.utils.SYSTEM_PROMPT_DATA_ANALYSIS
import org.example.utils.SYSTEM_PROMPT_WITH_SOURCES

class ChatLoop(
    private val console: ConsoleInput,
    private val useCases: UseCases,
    private val memoryRepository: MemoryRepository,
    private val ragService: RagService? = null,
    private val multiMcpClient: MultiMcpClient? = null,
    private val classpath: String? = null,
    private val ollamaClient: OllamaClient? = null,
    private val analysisService: DataAnalysisService? = null
) {
    private val commandRegistry = CommandRegistry(ragService, useCases.helpClient, useCases.mainClient, useCases.pipelineClient)

    suspend fun run() {
        printWelcome()

        val state = ChatState(
            currentSystemPrompt = SYSTEM_PROMPT_WITH_SOURCES,
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
        println("  /help [вопрос]  - интеллектуальный помощник по кодбазе")
        println("  /review-pr      - AI ревью Pull Request (с RAG контекстом)")
        println("  /auto-pr        - Создать PR с автоматическим ревью (полный цикл)")
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
        if (chatHistory.needsCompression()) {
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
                        topK = 2,
                        initialTopK = 5,
                        minSimilarity = 0.5f,
                        rerankerConfig = config
                    )

                    // Формируем информацию о результатах
                    val info = buildString {
                        if (result.finalResults.isNotEmpty()) {
                            appendLine("Найдено ${result.finalResults.size} релевантных фрагментов (с реранкингом):")
                            result.rerankedResults
                                .filter { !it.wasFiltered }
                                .take(2)
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
                    val ragContext = ragService.search(text, topK = 2, minSimilarity = 0.6f)
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
            // Check if data analysis mode is active
            if (state.currentSystemPrompt == SYSTEM_PROMPT_DATA_ANALYSIS &&
                ollamaClient != null && analysisService != null) {
                // Use tool calling for data analysis
                processDataAnalysisRequest(messageWithRag, chatHistory, state)
            } else {
                // Standard flow
                val conversationWithSystem = buildConversation(chatHistory, state.currentSystemPrompt)
                val answer = sendAndCollectResponse(conversationWithSystem, state)

                answer?.let {
                    ResponsePrinter.printResponse(it)
                    chatHistory.addMessage(ChatRole.ASSISTANT, it.message)
                    ResponsePrinter.printTokenStats(it, chatHistory)
                }
            }
        } catch (t: Throwable) {
            chatHistory.removeLastMessage()
            println()
            println("Ошибка при запросе: ${t.message}")
            println("(Сообщение не сохранено в историю)")
            println()
        }
    }

    /**
     * Process data analysis request with tool calling
     */
    private suspend fun processDataAnalysisRequest(
        userMessage: String,
        chatHistory: ChatHistory,
        state: ChatState
    ) {
        val client = ollamaClient ?: return
        val service = analysisService ?: return

        // Build tool definitions
        val tools = service.getToolDefinitions().map { tool ->
            OllamaToolDto(
                type = "function",
                function = OllamaFunctionDto(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }

        // Build conversation
        val messages = chatHistory.messages.map { msg ->
            LlmMessage(msg.role, msg.content)
        }

        print("...")
        System.out.flush()

        var request = LlmRequest(
            model = client.model,
            systemPrompt = state.currentSystemPrompt,
            messages = messages
        )

        // Tool calling loop (max 5 iterations to prevent infinite loops)
        var iterations = 0
        val maxIterations = 5
        val conversationMessages = messages.toMutableList()
        var finalResponse = ""

        while (iterations < maxIterations) {
            iterations++

            val response = client.sendWithTools(request, tools)
            val content = response.message.content

            // Check for structured tool calls first
            val toolCalls = response.message.toolCalls

            // Also check for text-based tool calls (qwen2.5 format)
            val textToolCall = parseTextToolCall(content)

            if (toolCalls.isNullOrEmpty() && textToolCall == null) {
                // No tool calls - we have the final response
                finalResponse = content
                break
            }

            // Execute tool calls
            print("\r[Выполняю инструменты...]")
            System.out.flush()

            // Handle structured tool calls
            if (!toolCalls.isNullOrEmpty()) {
                for (toolCall in toolCalls) {
                    val toolName = toolCall.function.name
                    val args = toolCall.function.arguments.entries.associate { (k, v) ->
                        k to when (v) {
                            is JsonPrimitive -> if (v.isString) v.content else v.toString()
                            else -> v.toString()
                        }
                    }

                    println("\n  → $toolName(${args.values.firstOrNull() ?: ""})")

                    val result = service.handleToolCall(toolName, args)

                    conversationMessages.add(LlmMessage(ChatRole.ASSISTANT, "Вызываю $toolName..."))
                    conversationMessages.add(LlmMessage(ChatRole.USER, "Результат $toolName:\n$result"))
                }
            }

            // Handle text-based tool calls (for models that don't use native tool calling)
            if (textToolCall != null) {
                val (toolName, args) = textToolCall
                println("\n  → $toolName(${args.values.firstOrNull() ?: ""})")

                val result = service.handleToolCall(toolName, args)

                conversationMessages.add(LlmMessage(ChatRole.ASSISTANT, content))
                conversationMessages.add(LlmMessage(ChatRole.USER, "Результат $toolName:\n$result\n\nТеперь проанализируй эти данные и ответь на вопрос пользователя."))
            }

            // Continue conversation with tool results
            request = LlmRequest(
                model = client.model,
                systemPrompt = state.currentSystemPrompt,
                messages = conversationMessages
            )
        }

        print("\r")

        if (finalResponse.isNotBlank()) {
            println()
            println(finalResponse)
            println()
            chatHistory.addMessage(ChatRole.ASSISTANT, finalResponse)
        } else {
            println()
            println("Модель не вернула финальный ответ после $maxIterations итераций")
            println()
        }
    }

    /**
     * Parse tool call from text content (for models that don't use native tool calling)
     * Supports multiple formats:
     * 1. JSON: {"name": "analyze_file", "arguments": {"path": "file.csv"}}
     * 2. Function call in code: analyze_file("file.csv")
     * 3. Markdown code block with function call
     */
    private fun parseTextToolCall(content: String): Pair<String, Map<String, Any?>>? {
        // Try JSON format first: {"name": "...", "arguments": {...}}
        val jsonPattern = """\{[^{}]*"name"\s*:\s*"([^"]+)"[^{}]*"arguments"\s*:\s*(\{[^{}]*\})[^{}]*\}""".toRegex()
        val jsonMatch = jsonPattern.find(content)
        if (jsonMatch != null) {
            return try {
                val toolName = jsonMatch.groupValues[1]
                val argsJson = jsonMatch.groupValues[2]

                val json = Json { ignoreUnknownKeys = true }
                val argsObject = json.parseToJsonElement(argsJson).jsonObject

                val args = argsObject.entries.associate { (k, v) ->
                    k to when {
                        v is JsonPrimitive && v.isString -> v.content
                        v is JsonPrimitive -> v.toString()
                        else -> v.toString()
                    }
                }

                Pair(toolName, args)
            } catch (e: Exception) {
                null
            }
        }

        // Try function call format: analyze_file("path") or execute_kotlin('''code''')
        val funcPattern = """(analyze_file|execute_kotlin|format_result)\s*\(\s*["'`]([^"'`]+)["'`]\s*\)""".toRegex()
        val funcMatch = funcPattern.find(content)
        if (funcMatch != null) {
            val toolName = funcMatch.groupValues[1]
            val arg = funcMatch.groupValues[2]

            val argName = when (toolName) {
                "analyze_file" -> "path"
                "execute_kotlin" -> "code"
                "format_result" -> "data"
                else -> "arg"
            }

            return Pair(toolName, mapOf(argName to arg))
        }

        // Try triple-quoted code for execute_kotlin
        val tripleQuotePattern = """execute_kotlin\s*\(\s*['"`]{3}([\s\S]*?)['"`]{3}\s*\)""".toRegex()
        val tripleMatch = tripleQuotePattern.find(content)
        if (tripleMatch != null) {
            return Pair("execute_kotlin", mapOf("code" to tripleMatch.groupValues[1].trim()))
        }

        return null
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
