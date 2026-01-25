package org.example.app

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.mcp.McpClient
import org.example.data.mcp.MultiMcpClient
import org.example.data.mcp.ToolHandler
import org.example.data.network.LlmClient
import org.example.data.network.HaikuSummaryClient
import org.example.data.network.SimpleSummaryClient
import org.example.data.network.SummaryClient
import org.example.data.network.ToolAwareClient
import org.example.data.repository.ChatRepositoryImpl
import org.example.domain.usecase.CompressHistoryUseCase
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput
import org.example.data.analysis.DataAnalysisService
import java.nio.file.Paths

data class UseCases(
    val sendMessage: SendMessageUseCase,
    val compressHistory: CompressHistoryUseCase,
    val helpClient: LlmClient?,
    val mainClient: LlmClient?,  // Для PR Review и других продвинутых команд (с MCP tools)
    val pipelineClient: LlmClient?  // Для FullCycle Pipeline (без MCP tools, работает с git напрямую)
)

object AppInitializer {

    fun resolveApiKey(console: ConsoleInput, envVar: String, serviceName: String): String? {
        val envKey = System.getenv(envVar)
        if (!envKey.isNullOrBlank()) return envKey

        val fromInput = console.readLine(
            "Переменная $envVar не установлена.\n" +
                    "Введите API ключ $serviceName вручную (или Enter для пропуска): "
        )?.trim()

        return if (fromInput.isNullOrEmpty()) {
            println("Ключ $serviceName не указан.")
            null
        } else {
            fromInput
        }
    }

    fun buildUseCases(
        client: HttpClient,
        json: Json,
        anthropicKey: String,
        openRouterKey: String?,
        mcpClient: McpClient? = null,
        multiMcpClient: MultiMcpClient? = null
    ): UseCases {
        val toolHandler = ToolHandler(mcpClient, multiMcpClient)

        val mainClient: LlmClient = if (mcpClient != null || multiMcpClient != null) {
            // Use Haiku for tool-aware client (better rate limits for MCP operations)
            ToolAwareClient(
                http = client,
                json = json,
                apiKey = anthropicKey,
                model = AppConfig.CLAUDE_HAIKU_MODEL,
                toolHandler = toolHandler
            )
        } else {
            // Fallback to Sonnet for regular client (no tools)
            AnthropicClient(
                http = client,
                json = json,
                apiKey = anthropicKey,
                model = AppConfig.CLAUDE_SONNET_MODEL,
            )
        }

        val clients: List<LlmClient> = listOf(mainClient)

        val chatRepository = ChatRepositoryImpl(clients = clients)

        val summaryClient: SummaryClient = HaikuSummaryClient(
            http = client,
            json = json,
            apiKey = anthropicKey
        )

        val compressHistory = CompressHistoryUseCase(summaryClient)

        // Создаём отдельный Haiku клиент для /help команды
        val helpClient: LlmClient = AnthropicClient(
            http = client,
            json = json,
            apiKey = anthropicKey,
            model = AppConfig.CLAUDE_HAIKU_MODEL
        )

        // Создаём отдельный Sonnet клиент для pipeline (без MCP tools)
        // Pipeline работает с git напрямую и не нуждается в MCP инструментах
        val pipelineClient: LlmClient = AnthropicClient(
            http = client,
            json = json,
            apiKey = anthropicKey,
            model = AppConfig.CLAUDE_SONNET_MODEL
        )

        return UseCases(
            sendMessage = SendMessageUseCase(chatRepository),
            compressHistory = compressHistory,
            helpClient = helpClient,
            mainClient = mainClient,
            pipelineClient = pipelineClient
        )
    }

    /**
     * Build use cases with a custom LLM client (e.g., Ollama)
     * Uses the same client for all operations
     */
    fun buildUseCasesWithCustomClient(
        customClient: LlmClient,
        mcpClient: McpClient? = null,
        multiMcpClient: MultiMcpClient? = null
    ): UseCases {
        val toolHandler = ToolHandler(mcpClient, multiMcpClient)

        // Use custom client for main operations
        val mainClient: LlmClient = if (mcpClient != null || multiMcpClient != null) {
            // Wrap with ToolAwareClient if MCP is present
            // Note: This may not work optimally with Ollama as it's designed for Claude
            customClient  // For MVP, use custom client directly
        } else {
            customClient
        }

        val clients: List<LlmClient> = listOf(mainClient)
        val chatRepository = ChatRepositoryImpl(clients = clients)

        // For Ollama: use simple summary client (no LLM calls)
        val compressHistory = CompressHistoryUseCase(
            summaryClient = SimpleSummaryClient()
        )

        return UseCases(
            sendMessage = SendMessageUseCase(chatRepository),
            compressHistory = compressHistory,
            helpClient = customClient,      // Use same client for help
            mainClient = customClient,      // Use same client for main operations
            pipelineClient = customClient   // Use same client for pipeline
        )
    }

    /**
     * Создаёт сервис анализа данных для текущей директории
     */
    fun createAnalysisService(): DataAnalysisService {
        val workingDir = Paths.get(System.getProperty("user.dir"))
        return DataAnalysisService(workingDir)
    }
}
