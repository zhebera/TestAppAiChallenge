package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.network.LlmClient
import org.example.data.repository.ChatRepositoryImpl
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC_TEACHER
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC_TOKAR
import org.example.utils.prettyOutput

// --- Константы и конфигурация ---
private const val CLAUDE_SONNET_MODEL_NAME = "claude-sonnet-4-20250514"
private const val CLAUDE_HAIKU_MODEL_NAME = "claude-haiku-4-5-20251001"
private const val CLAUDE_OPUS_MODEL_NAME = "claude-opus-4-1"

fun main() = runBlocking {
    val console = ConsoleInput()

    val apiKey = resolveApiKey(console) ?: return@runBlocking

    val json = buildJsonConfig()
    val client = buildHttpClient(json)

    try {
        val sendMessageUseCase = buildSendMessageUseCase(client, json, apiKey)
        runChatLoop(console, sendMessageUseCase)
    } finally {
        client.close()
    }
}

private fun resolveApiKey(console: ConsoleInput): String? {
    val envKey = System.getenv("ANTHROPIC_API_KEY")
    if (!envKey.isNullOrBlank()) return envKey

    val fromInput = console.readLine(
        "Переменная ANTHROPIC_API_KEY не установлена.\n" +
                "Введите API ключ Anthropic вручную: "
    )?.trim()

    return if (fromInput.isNullOrEmpty()) {
        println("\nAPI ключ не указан или ввод недоступен. Завершаю работу.")
        null
    } else {
        fromInput
    }
}

private fun buildJsonConfig(): Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

private fun buildHttpClient(json: Json): HttpClient =
    HttpClient(CIO) {

        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 150_000
            connectTimeoutMillis = 100_000
            socketTimeoutMillis = 150_000
        }
    }

private fun buildSendMessageUseCase(
    client: HttpClient,
    json: Json,
    apiKey: String
): SendMessageUseCase {
    val claudeSonnetClient = AnthropicClient(
        http = client,
        json = json,
        apiKey = apiKey,
        model = CLAUDE_SONNET_MODEL_NAME,
        systemPrompt = SYSTEM_FORMAT_PROMPT_LOGIC,
    )

    val claudeHaikuClient = AnthropicClient(
        http = client,
        json = json,
        apiKey = apiKey,
        model = CLAUDE_HAIKU_MODEL_NAME,
        systemPrompt = SYSTEM_FORMAT_PROMPT_LOGIC_TOKAR,
    )

    val claudeOpusClient = AnthropicClient(
        http = client,
        json = json,
        apiKey = apiKey,
        model = CLAUDE_OPUS_MODEL_NAME,
        systemPrompt = SYSTEM_FORMAT_PROMPT_LOGIC_TEACHER,
    )

    val clients: List<LlmClient> = listOf(claudeSonnetClient, claudeHaikuClient, claudeOpusClient)
//    val clients: List<LlmClient> = listOf(claudeSonnetClient)

    val chatRepository = ChatRepositoryImpl(
        clients = clients,
        json = json,
    )

    return SendMessageUseCase(chatRepository)
}

private suspend fun runChatLoop(
    console: ConsoleInput,
    sendMessageUseCase: SendMessageUseCase
) {
    println("Multi-LLM Chat. Введите 'exit' для выхода.\n")

    val conversation = mutableListOf<LlmMessage>()

    while (true) {
//        println("[DEBUG] Ждём ввода пользователя...")
        val line = console.readLine("user >> ") ?: run {
            println("\nВвод недоступен (EOF/ошибка). Выход из программы.")
            break
        }
//        println("[DEBUG] Получена строка: '$line'")

        val text = line.trim()
        if (text.equals("exit", ignoreCase = true)) {
            println("Выход.")
            break
        }
        if (text.isEmpty()) {
            continue
        }

        conversation += LlmMessage(
            role = ChatRole.USER,
            content = text
        )

        try {
            val answers: List<LlmAnswer> = sendMessageUseCase(conversation)

            val mainAnswer = answers.firstOrNull()
            if (mainAnswer != null) {
                conversation += LlmMessage(
                    role = ChatRole.ASSISTANT,
                    content = mainAnswer.message
                )
            }

            for (answer in answers) {
//                println()
//                println("=== ${answer.model} ===")
//                println()

                if (answer.phase == "ready" && answer.document.isNotBlank()) {
                    println("ГОТОВОЕ РЕШЕНИЕ:")
                    println()
                    println(prettyOutput(answer.document, maxWidth = 120))
                    println()
                } else {
                    println(prettyOutput(answer.message, maxWidth = 120))
                    println()
                }
            }
        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }
}