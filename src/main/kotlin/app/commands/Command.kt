package org.example.app.commands

import org.example.app.UseCases
import org.example.data.persistence.MemoryRepository
import org.example.domain.models.ChatHistory
import org.example.presentation.ConsoleInput

data class ChatState(
    var currentSystemPrompt: String,
    var currentTemperature: Double?,
    var currentMaxTokens: Int
)

data class CommandContext(
    val console: ConsoleInput,
    val chatHistory: ChatHistory,
    val memoryRepository: MemoryRepository,
    val useCases: UseCases,
    val state: ChatState
)

sealed class CommandResult {
    data object Continue : CommandResult()
    data object Exit : CommandResult()
    data object NotHandled : CommandResult()
}

interface Command {
    fun matches(input: String): Boolean
    suspend fun execute(input: String, context: CommandContext): CommandResult
}
