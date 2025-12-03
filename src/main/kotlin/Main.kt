package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicApi
import org.example.data.repository.AnthropicChatRepositoryImpl
import org.example.domain.models.ChatMessage
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput
import org.example.utils.prettyOutput

// --- Константы и конфигурация ---

private const val MODEL_NAME = "claude-sonnet-4-20250514"

private val SYSTEM_FORMAT_PROMPT = """
    Ты — помощник аналитика. Твоя задача — по ходу диалога с пользователем собрать достаточно информации о проекте, а затем выдать ему готовое техническое задание (ТЗ).
    
    ТВОЁ ПОВЕДЕНИЕ:
    
    1. Режим вопросов (phase = "questions")
    - В начале диалога выясни, что за проект и для чего он нужен.
    - Далее веди себя как опытный аналитик:
      - сам определяй, какие аспекты проекта нужно уточнить (бизнес-цели, пользователи, сценарии, ограничения, риски и т.д.);
      - задавай осмысленные, последовательные вопросы;
      - иногда кратко резюмируй, что уже понял.
    - На каждом шаге думай про себя: "Хватит ли мне текущей информации, чтобы написать полезное и понятное ТЗ? Чего мне ещё не хватает?".
    - Пока чувствуешь, что есть ещё важные пробелы — оставайся в режиме вопросов (phase = "questions").
    
    2. Режим готового ТЗ (phase = "ready")
    - Когда, по твоему мнению, информации уже достаточно, чтобы написать полезное ТЗ:
      - переключись на phase = "ready";
      - в поле "message" кратко сообщи, что готов представить итоговый документ (например, "ГОТОВОЕ ТЗ:");
      - в поле "tz_document" сформируй полный текст ТЗ на русском языке.
    - Структуру ТЗ выбери сам как аналитик, но сделай документ понятным и логичным: от общего к частному (описание проекта, цели, пользователи, основные функции, ограничения и т.д.).
    - В режиме "ready" НЕ задавай больше вопросов, только выдавай итоговый документ.
    - Старайся делать ТЗ максимально компактным и практичным:
        • не больше ~800–1200 слов;
        • не расписывай очевидные вещи;
        • в каждом разделе делай 3–7 ключевых пунктов, а не 20.
    
    ФОРМАТ ОТВЕТА (ВСЕГДА):
    
    Всегда отвечай строго в формате JSON БЕЗ лишнего текста до или после.
    
    Схема JSON-ответа:
    
    {
      "phase": "questions" | "ready",
      "message": "строка — что сказать пользователю сейчас (вопрос/пояснение или фраза перед ТЗ)",
      "document": "строка — полный текст ТЗ; пустая строка, пока ТЗ ещё не сформировано"
    }
    
    СТРОГИЕ ПРАВИЛА:
    - НЕЛЬЗЯ использовать markdown-блоки вида ```json ... ``` или любые ``` ... ``` блоки.
    - НЕЛЬЗЯ добавлять текст вне JSON-объекта.
    - НЕЛЬЗЯ экранировать JSON целиком в строке. Ответ должен быть настоящим JSON-объектом, начинающимся с { и заканчивающимся }.
    - Пока ты уточняешь требования — устанавливай "phase": "questions" и оставляй "document": "" (пустая строка).
    - Когда считаешь, что данных достаточно для итогового ТЗ, устанавливай "phase": "ready" и заполняй "document" полным текстом ТЗ.
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

            if (answer.phase == "ready" && answer.document.isNotBlank()) {
                println()
                println("==== ГОТОВОЕ ТЗ ====")
                println()
                println(prettyOutput(answer.document, maxWidth = 150))
                println()
                println("==== КОНЕЦ ТЗ ====")
                println()
            } else {
                println()
                println(prettyOutput(answer.text, maxWidth = 150))
                println()
            }

        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }
}