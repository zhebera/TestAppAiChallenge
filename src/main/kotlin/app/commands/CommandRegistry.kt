package org.example.app.commands

import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.fullcycle.FullCycleCommand

class CommandRegistry(
    ragService: RagService? = null,
    helpClient: LlmClient? = null,
    mainLlmClient: LlmClient? = null
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
    ) + (if (mainLlmClient != null) listOf(
        ReviewPrCommand(mainLlmClient, ragService),  // Ревью PR
        AutoPrCommand(mainLlmClient, ragService),    // Автоматический PR с ревью
        FullCycleCommand(mainLlmClient, ragService)  // Полный цикл: код → PR → review → merge
    ) else emptyList())

    suspend fun tryExecute(input: String, context: CommandContext): CommandResult {
        for (command in commands) {
            if (command.matches(input)) {
                return command.execute(input, context)
            }
        }
        return CommandResult.NotHandled
    }
}
