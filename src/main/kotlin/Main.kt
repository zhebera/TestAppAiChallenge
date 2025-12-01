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

fun main() = runBlocking {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
        ?: error("Переменная окружения ANTHROPIC_API_KEY не установлена")

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
    val conversation = mutableListOf<ChatMessage>()

    println("Anthropic Kotlin Chat (clean). Введите 'exit' для выхода.")
    println()

    while (true) {
        print("user >> ")
        val line = readlnOrNull() ?: break
        if (line.lowercase() == "exit") break
        if (line.isBlank()) continue

        conversation += ChatMessage(
            role = "user",
            content = line
        )

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
            t.printStackTrace()
            println()
        }
    }

    client.close()
}