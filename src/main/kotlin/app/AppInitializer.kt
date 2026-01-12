package org.example.app

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.mcp.McpClient
import org.example.data.mcp.MultiMcpClient
import org.example.data.mcp.ToolHandler
import org.example.data.network.LlmClient
import org.example.data.network.HaikuSummaryClient
import org.example.data.network.SummaryClient
import org.example.data.network.ToolAwareClient
import org.example.data.repository.ChatRepositoryImpl
import org.example.domain.usecase.CompressHistoryUseCase
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput

data class UseCases(
    val sendMessage: SendMessageUseCase,
    val compressHistory: CompressHistoryUseCase,
    val helpClient: LlmClient?
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
            // Use tool-aware client when MCP is available
            ToolAwareClient(
                http = client,
                json = json,
                apiKey = anthropicKey,
                model = AppConfig.CLAUDE_SONNET_MODEL,
                toolHandler = toolHandler
            )
        } else {
            // Fallback to regular client
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

        return UseCases(
            sendMessage = SendMessageUseCase(chatRepository),
            compressHistory = compressHistory,
            helpClient = helpClient
        )
    }
}
