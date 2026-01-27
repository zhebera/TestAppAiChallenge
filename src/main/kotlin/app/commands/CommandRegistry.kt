package org.example.app.commands

import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.fullcycle.FullCycleCommand

class CommandRegistry(
    ragService: RagService? = null,
    helpClient: LlmClient? = null,
    mainLlmClient: LlmClient? = null,
    pipelineClient: LlmClient? = null  // Отдельный клиент для pipeline (без MCP tools)
) {
    private val commands: List<Command> = listOf(
        ExitCommand(),
        NewSessionCommand(),
        ProfileCommand(),
        StatsCommand(),
        TemperatureCommand(),
        MaxTokensCommand(),
        MemoryCommand(),
        ChangePromptCommand(),
        McpControlCommand(),  // Управление локальными MCP серверами
        RagCommand(ragService),
        HelpCommand(ragService, helpClient),
        VoiceCommand()  // Голосовой ввод
    ) + (if (mainLlmClient != null) listOf(
        ReviewPrCommand(mainLlmClient, ragService),  // Ревью PR
        AutoPrCommand(mainLlmClient, ragService)     // Автоматический PR с ревью
    ) else emptyList()) + (if (pipelineClient != null) listOf(
        FullCycleCommand(pipelineClient, ragService)  // Полный цикл (без MCP - работает с git напрямую)
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
