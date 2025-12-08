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
import org.example.utils.SYSTEM_FORMAT_PROMPT_PIRATE
import org.example.utils.SYSTEM_FORMAT_PROMPT_TOKAR
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
//        systemPrompt = SYSTEM_FORMAT_PROMPT_LOGIC,
    )

    val clients: List<LlmClient> = listOf(claudeSonnetClient)

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
    println("LLM Chat. Введите 'exit' для выхода.\n")
    println("Для смены System Prompt введите '/changePrompt' ")

    var currentSystemPrompt: String = SYSTEM_FORMAT_PROMPT_LOGIC
    val conversation = mutableListOf<LlmMessage>()

    while (true) {
        val line = console.readLine("user >> ") ?: run {
            println("\nВвод недоступен (EOF/ошибка). Выход из программы.")
            break
        }

        val text = line.trim()
        if (text.equals("exit", ignoreCase = true)) {
            println("Выход.")
            break
        }
        if (text.isEmpty()) {
            continue
        }

        if (text.equals("/changePrompt", ignoreCase = true)) {
            println()
            println("Выберите новый system prompt:")
            println("1 - Логические задачи (SYSTEM_FORMAT_PROMPT_LOGIC)")
            println("2 - Токарь (SYSTEM_FORMAT_PROMPT_TECH)")
            println("3 - Пират 18 века (SYSTEM_FORMAT_PROMPT_TECH)")
            println("4 - Свободный режим (без system prompt)")
            print("Ваш выбор (1/2/3): ")

            val choice = console.readLine("")?.trim()

            currentSystemPrompt = when (choice) {
                "1" -> SYSTEM_FORMAT_PROMPT_LOGIC
                "2" -> SYSTEM_FORMAT_PROMPT_TOKAR
                "3" -> SYSTEM_FORMAT_PROMPT_PIRATE
                "4" -> ""
                else -> {
                    println("Неизвестный выбор, оставляю прежний system prompt.")
                    currentSystemPrompt
                }
            }
            val role = when (currentSystemPrompt) {
                SYSTEM_FORMAT_PROMPT_LOGIC -> "помощник по решению логических, математических и головоломных задач"
                SYSTEM_FORMAT_PROMPT_TOKAR -> "опытный токарь с 25-летним стажем, мастер по металлообработке"
                SYSTEM_FORMAT_PROMPT_PIRATE -> "пират 18 века"
                else -> ""
            }

            if (role.isNotEmpty()) {
                println("System prompt обновлён на $role.")
                println()
            }
            continue
        }

        conversation += LlmMessage(
            role = ChatRole.USER,
            content = text
        )

        try {
            val conversationWithSystem: List<LlmMessage> =
                if (currentSystemPrompt.isNotBlank()) {
                    listOf(
                        LlmMessage(
                            role = ChatRole.SYSTEM,
                            content = currentSystemPrompt
                        )
                    ) + conversation
                } else {
                    conversation
                }

            val answers: List<LlmAnswer> = sendMessageUseCase(conversationWithSystem)

            val mainAnswer = answers.firstOrNull()
            if (mainAnswer != null) {
                conversation += LlmMessage(
                    role = ChatRole.ASSISTANT,
                    content = mainAnswer.message
                )
            }

            for (answer in answers) {
                if (answer.phase == "ready" && answer.document.isNotBlank()) {
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