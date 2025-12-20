package org.example.app

import org.example.app.commands.ChatState
import org.example.app.commands.CommandContext
import org.example.app.commands.CommandRegistry
import org.example.app.commands.CommandResult
import org.example.data.persistence.MemoryRepository
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
    private val memoryRepository: MemoryRepository
) {
    private val commandRegistry = CommandRegistry()

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
            state = state
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
        println("  /mcp            - подключение к GitHub MCP")
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

        chatHistory.addMessage(ChatRole.USER, text)

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
