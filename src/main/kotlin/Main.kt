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

fun main() = runBlocking {
    val console = ConsoleInput()

    // 1. Получаем API ключ
    val envKey = System.getenv("ANTHROPIC_API_KEY")
    val apiKey = if (!envKey.isNullOrBlank()) {
        envKey
    } else {
        val fromInput = console.readLine(
            "Переменная ANTHROPIC_API_KEY не установлена.\n" +
                    "Введите API ключ Anthropic вручную: "
        )?.trim()

        if (fromInput.isNullOrEmpty()) {
            println("\nAPI ключ не указан или ввод недоступен. Завершаю работу.")
            return@runBlocking
        } else {
            fromInput
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    val api = AnthropicApi(
        client = client,
        json = json,
        apiKey = apiKey
    )

    val chatRepository = AnthropicChatRepositoryImpl(
        api = api,
        json = json,
        modelName = "claude-sonnet-4-20250514"
    )
    val sendMessageUseCase = SendMessageUseCase(chatRepository)

    // История диалога (domain-модель)
    println("Anthropic Kotlin Chat (clean). Введите 'exit' для выхода.\n")

    val conversation = mutableListOf<ChatMessage>()

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
            println("assistant >> ${answer.text}")
            println()
        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }

    client.close()
}