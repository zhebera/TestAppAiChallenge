package org.example.app.commands

class CommandRegistry {
    private val commands: List<Command> = listOf(
        ExitCommand(),
        NewSessionCommand(),
        StatsCommand(),
        TemperatureCommand(),
        MaxTokensCommand(),
        MemoryCommand(),
        ChangePromptCommand()
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
