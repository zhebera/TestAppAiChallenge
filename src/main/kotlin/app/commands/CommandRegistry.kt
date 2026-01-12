package org.example.app.commands

import org.example.data.network.LlmClient
import org.example.data.rag.RagService

class CommandRegistry(
    ragService: RagService? = null,
    helpClient: LlmClient? = null
) {
    private val commands: List<Command> = listOf(
        ExitCommand(),
        NewSessionCommand(),
        StatsCommand(),
        TemperatureCommand(),
        MaxTokensCommand(),
        MemoryCommand(),
        ChangePromptCommand(),
        McpControlCommand(),  // Управление локальными MCP серверами
        RagCommand(ragService),
        HelpCommand(ragService, helpClient)
    )

    suspend fun tryExecute(input: String, context: CommandContext): CommandResult {
        for (command in commands) {
            if (command.matches(input)) {
                return command.execute(input, context)
            }
        }
        return CommandResult.NotHandled
    }
}
