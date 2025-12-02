package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicApi
import org.example.data.repository.AnthropicChatRepositoryImpl
import org.example.domain.models.ChatMessage
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput

// --- Константы и конфигурация ---

private const val MODEL_NAME = "claude-sonnet-4-20250514"

private val SYSTEM_FORMAT_PROMPT = """
    Ты — полезный ассистент.

    Всегда отвечай строго в формате JSON БЕЗ лишнего текста до или после.

    СТРОГИЕ ПРАВИЛА ФОРМАТА:
    - НЕЛЬЗЯ использовать markdown-блоки вида ```json ... ```
    - НЕЛЬЗЯ использовать обычные блоки ``` ... ```
    - НЕЛЬЗЯ экранировать JSON внутри строки — должен быть настоящий JSON-объект, начинающийся с { и заканчивающийся }.
    - НЕЛЬЗЯ добавлять текст до или после JSON.
    - Ответ должен быть СТРОГО валидным JSON.

    Схема ответа:

    {
      "answer": "строка",
      "details": "строка",
      "language": "строка"
    }
""".trimIndent()

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
    }

private fun buildSendMessageUseCase(
    client: HttpClient,
    json: Json,
    apiKey: String
): SendMessageUseCase {
    val api = AnthropicApi(
        client = client,
        json = json,
        apiKey = apiKey
    )

    val repository = AnthropicChatRepositoryImpl(
        api = api,
        json = json,
        modelName = MODEL_NAME
    )

    return SendMessageUseCase(repository)
}

private suspend fun runChatLoop(
    console: ConsoleInput,
    sendMessageUseCase: SendMessageUseCase
) {
    println("Anthropic Kotlin Chat (clean). Введите 'exit' для выхода.\n")

    val conversation = mutableListOf(
        ChatMessage(
            role = "system",
            content = SYSTEM_FORMAT_PROMPT
        )
    )

    while (true) {
        val line = console.readLine("user >> ") ?: run {
            println("\nВвод недоступен (EOF/ошибка). Выход из программы.")
            break
        }

        val text = line.trim()
        if (text.equals("exit", ignoreCase = true)) break
        if (text.isEmpty()) continue

        conversation += ChatMessage(role = "user", content = text)

        try {
            val answer = sendMessageUseCase(conversation)

            conversation += ChatMessage(
                role = "assistant",
                content = answer.text
            )

            println()
            println(answer.rawJson)
            println()
        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }
}