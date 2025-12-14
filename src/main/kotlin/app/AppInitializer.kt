package org.example.app

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.network.LlmClient
import org.example.data.network.OpenRouterSummaryClient
import org.example.data.network.SummaryClient
import org.example.data.repository.ChatRepositoryImpl
import org.example.domain.usecase.CompressHistoryUseCase
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput

data class UseCases(
    val sendMessage: SendMessageUseCase,
    val compressHistory: CompressHistoryUseCase?
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
        openRouterKey: String?
    ): UseCases {
        val claudeSonnetClient = AnthropicClient(
            http = client,
            json = json,
            apiKey = anthropicKey,
            model = AppConfig.CLAUDE_SONNET_MODEL,
        )

        val clients: List<LlmClient> = listOf(claudeSonnetClient)

        val chatRepository = ChatRepositoryImpl(clients = clients)

        val summaryClient: SummaryClient? = openRouterKey?.let {
            OpenRouterSummaryClient(
                http = client,
                json = json,
                apiKey = it
            )
        }

        val compressHistory = summaryClient?.let { CompressHistoryUseCase(it) }

        return UseCases(
            sendMessage = SendMessageUseCase(chatRepository),
            compressHistory = compressHistory
        )
    }
}
