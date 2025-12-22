package org.example.app.commands

import org.example.data.rag.RagService

class CommandRegistry(
    ragService: RagService? = null
) {
    private val commands: List<Command> = listOf(
        ExitCommand(),
        NewSessionCommand(),
        StatsCommand(),
        TemperatureCommand(),
        MaxTokensCommand(),
        MemoryCommand(),
        ChangePromptCommand(),
        McpCommand(),
        RagCommand(ragService)
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
