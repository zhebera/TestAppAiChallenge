package org.example.app.commands

import org.example.app.UseCases
import org.example.data.mcp.MultiMcpClient
import org.example.data.persistence.MemoryRepository
import org.example.domain.models.ChatHistory
import org.example.presentation.ConsoleInput

data class ChatState(
    var currentSystemPrompt: String,
    var currentTemperature: Double?,
    var currentMaxTokens: Int,
    var ragEnabled: Boolean = false,      // Автоматический RAG в чате (выключен по умолчанию, используй /rag on)
    var ragDebug: Boolean = false,        // Показывать полный запрос с RAG-контекстом
    var rerankerEnabled: Boolean = true,  // Включить реранкинг результатов
    var rerankerThreshold: Float = 0.4f,  // Порог отсечения после реранкинга (0.0-1.0)
    var rerankerMethod: String = "cross"  // Метод реранкинга: cross, llm, keyword
)

data class CommandContext(
    val console: ConsoleInput,
    val chatHistory: ChatHistory,
    val memoryRepository: MemoryRepository,
    val useCases: UseCases,
    val state: ChatState,
    val multiMcpClient: MultiMcpClient? = null,  // Для управления MCP серверами
    val classpath: String? = null                 // Для подключения новых MCP серверов
)

sealed class CommandResult {
    data object Continue : CommandResult()
    data object Exit : CommandResult()
    data object NotHandled : CommandResult()
    data class VoiceInput(val text: String) : CommandResult()
}

interface Command {
    fun matches(input: String): Boolean
    suspend fun execute(input: String, context: CommandContext): CommandResult
}
