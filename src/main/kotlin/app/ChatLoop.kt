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
     * Process data analysis request - simplified approach without relying on model tool calling
     * 1. Auto-detect file in user message and load it
     * 2. Send data to model and let it answer
     * 3. If model writes Kotlin code, execute it automatically
     */
    private suspend fun processDataAnalysisRequest(
        userMessage: String,
        chatHistory: ChatHistory,
        state: ChatState
    ) {
        val client = ollamaClient ?: return
        val service = analysisService ?: return

        print("[Анализ...]")
        System.out.flush()

        // Step 1: Auto-detect and load file if mentioned
        val filePattern = """([\w\-_]+\.(csv|json|log|txt))""".toRegex(RegexOption.IGNORE_CASE)
        val fileMatch = filePattern.find(userMessage)

        var contextWithData = userMessage
        var fileData: String? = null

        if (fileMatch != null) {
            val fileName = fileMatch.groupValues[1]
            print("\r[Загружаю $fileName...]")
            System.out.flush()

            val analysisResult = service.handleToolCall("analyze_file", mapOf("path" to fileName))

            if (!analysisResult.contains("не найден")) {
                fileData = analysisResult
                contextWithData = """
                    |ДАННЫЕ ФАЙЛА $fileName:
                    |$analysisResult
                    |
                    |ВОПРОС ПОЛЬЗОВАТЕЛЯ: $userMessage
                    |
                    |Проанализируй данные и ответь на вопрос. Если нужно посчитать что-то, напиши Kotlin-код в блоке ```kotlin ... ```
                """.trimMargin()
            }
        }

        // Step 2: Send to model
        val simplePrompt = """
            |Ты аналитик данных. Отвечай кратко и по делу.
            |Если нужно посчитать что-то в данных, напиши код в блоке ```kotlin ... ```
            |Переменная `data` содержит содержимое файла как строку.
        """.trimMargin()

        val request = LlmRequest(
            model = client.model,
            systemPrompt = simplePrompt,
            messages = listOf(LlmMessage(ChatRole.USER, contextWithData))
        )

        print("\r[Думаю...]      ")
        System.out.flush()

        val response = client.send(request)
        var answer = response.text

        // Step 3: If model wrote Kotlin code, execute it
        val codePattern = """```kotlin\s*([\s\S]*?)```""".toRegex()
        val codeMatch = codePattern.find(answer)

        if (codeMatch != null && fileData != null) {
            val code = codeMatch.groupValues[1].trim()
            print("\r[Выполняю код...]")
            System.out.flush()

            val execResult = service.handleToolCall("execute_kotlin", mapOf("code" to code))

            // Replace code block with result or append result
            answer = answer.replace(codeMatch.value, "")
            answer = "$answer\n\n**Результат:** $execResult"
        }

        print("\r                    \r")

        println()
        println(answer.trim())
        println()
        chatHistory.addMessage(ChatRole.ASSISTANT, answer.trim())
    }

    /**
     * Parse tool call from text content (for models that don't use native tool calling)
     * Supports multiple formats that qwen2.5 might output
     */
    private fun parseTextToolCall(content: String): Pair<String, Map<String, Any?>>? {
        // Normalize common typos/variations in tool names
        val normalizedContent = content
            .replace("letonalyze_file", "analyze_file")
            .replace("letexecute_kotlin", "execute_kotlin")
            .replace("letformat_result", "format_result")

        // Format 1: JSON {"name": "...", "arguments": {...}}
        val jsonPattern = """\{[^{}]*"name"\s*:\s*"([^"]+)"[^{}]*"arguments"\s*:\s*(\{[^{}]*\})[^{}]*\}""".toRegex()
        val jsonMatch = jsonPattern.find(normalizedContent)
        if (jsonMatch != null) {
            return try {
                val toolName = normalizeToolName(jsonMatch.groupValues[1])
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

        // Format 2: tool_name, {"arg": "value"} (qwen format)
        val commaJsonPattern = """(analyze_file|execute_kotlin|format_result)\s*,\s*(\{[^{}]+\})""".toRegex(RegexOption.IGNORE_CASE)
        val commaMatch = commaJsonPattern.find(normalizedContent)
        if (commaMatch != null) {
            return try {
                val toolName = normalizeToolName(commaMatch.groupValues[1])
                val argsJson = commaMatch.groupValues[2]

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

        // Format 3: function call analyze_file("path")
        val funcPattern = """(analyze_file|execute_kotlin|format_result)\s*\(\s*["'`]([^"'`]+)["'`]\s*\)""".toRegex(RegexOption.IGNORE_CASE)
        val funcMatch = funcPattern.find(normalizedContent)
        if (funcMatch != null) {
            val toolName = normalizeToolName(funcMatch.groupValues[1])
            val arg = funcMatch.groupValues[2]

            val argName = when (toolName) {
                "analyze_file" -> "path"
                "execute_kotlin" -> "code"
                "format_result" -> "data"
                else -> "arg"
            }

            return Pair(toolName, mapOf(argName to arg))
        }

        // Format 4: execute_kotlin with multi-line code in parentheses
        val multiLinePattern = """execute_kotlin\s*\(([\s\S]*?)\)(?:\s*$|\s*\n)""".toRegex(RegexOption.IGNORE_CASE)
        val multiLineMatch = multiLinePattern.find(normalizedContent)
        if (multiLineMatch != null) {
            val code = multiLineMatch.groupValues[1]
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("```kotlin", "```")
                .removeSurrounding("```", "```")
                .trim()
            if (code.isNotBlank()) {
                return Pair("execute_kotlin", mapOf("code" to code))
            }
        }

        // Format 5: Look for file mentions with analyze intent
        val fileMentionPattern = """(?:анализ|посмотр|открой|покажи|файл)[^\n]*?([\w\-_]+\.(csv|json|log|txt))""".toRegex(RegexOption.IGNORE_CASE)
        val fileMatch = fileMentionPattern.find(normalizedContent)
        if (fileMatch != null && normalizedContent.contains("analyze_file", ignoreCase = true)) {
            return Pair("analyze_file", mapOf("path" to fileMatch.groupValues[1]))
        }

        return null
    }

    private fun normalizeToolName(name: String): String {
        return when (name.lowercase()) {
            "analyze_file", "analysefile", "analyzefile" -> "analyze_file"
            "execute_kotlin", "executekotlin" -> "execute_kotlin"
            "format_result", "formatresult" -> "format_result"
            else -> name.lowercase()
        }
    }

    /**
     * Check if model tried to call a tool but in wrong format
     */
    private fun looksLikeIncompleteToolCall(content: String): Boolean {
        val indicators = listOf(
            "analyze_file", "execute_kotlin", "format_result",
            "tool_call", "</tool_call>", "<tool_call>",
            "Вызываю", "вызываю",
            "letonalyze", "letexecute"
        )
        return indicators.any { content.contains(it, ignoreCase = true) }
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
