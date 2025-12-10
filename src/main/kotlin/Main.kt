package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.network.LlmClient
import org.example.data.repository.ChatRepositoryImpl
import org.example.data.repository.StreamResult
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput
import org.example.utils.SYSTEM_FORMAT_PROMPT
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC
import org.example.utils.SYSTEM_FORMAT_PROMPT_PIRATE
import org.example.utils.SYSTEM_FORMAT_PROMPT_TOKAR

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
        encodeDefaults = false
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
    )

    val clients: List<LlmClient> = listOf(claudeSonnetClient)

    val chatRepository = ChatRepositoryImpl(
        clients = clients,
    )

    return SendMessageUseCase(chatRepository)
}

private suspend fun runChatLoop(
    console: ConsoleInput,
    sendMessageUseCase: SendMessageUseCase
) {
    println("LLM Chat. Введите 'exit' для выхода.\n")
    println("Для смены System Prompt введите '/changePrompt'")
    println("Для изменения temperature введите '/temperature' (0.0 - 1.0)")
    println("Для изменения max_tokens введите '/maxTokens' (например: /maxTokens 100)")

    var currentSystemPrompt: String = SYSTEM_FORMAT_PROMPT
    var currentTemperature: Double? = null
    var currentMaxTokens = 1024
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

        if (text.startsWith("/temperature", ignoreCase = true)) {
            val parts = text.split(" ", limit = 2)
            if (parts.size == 2) {
                val value = parts[1].toDoubleOrNull()
                if (value != null && value in 0.0..1.0) {
                    currentTemperature = value
                    println("Temperature установлен: $value")
                    println()
                } else {
                    println("Некорректное значение. Введите число от 0.0 до 1.0")
                    println()
                }
            } else {
                println("Текущий temperature: ${currentTemperature ?: "не установлен (по умолчанию)"}")
                println("Использование: /temperature <значение от 0.0 до 1.0>")
                println("Пример: /temperature 0.7")
                println()
            }
            continue
        }

        if (text.startsWith("/maxTokens", ignoreCase = true)) {
            val parts = text.split(" ", limit = 2)
            if (parts.size == 2) {
                val value = parts[1].toIntOrNull()
                if (value != null && value > 0) {
                    currentMaxTokens = value
                    println("Max tokens установлен: $value")
                    println("(Установите маленькое значение, например 50, чтобы увидеть stop_reason='max_tokens')")
                    println()
                } else {
                    println("Некорректное значение. Введите положительное число")
                    println()
                }
            } else {
                println("Текущий max_tokens: $currentMaxTokens")
                println("Использование: /maxTokens <число>")
                println("Пример: /maxTokens 100  - маленький лимит (ответ будет обрезан)")
                println("Пример: /maxTokens 4096 - большой лимит")
                println()
            }
            continue
        }

        if (text.equals("/changePrompt", ignoreCase = true)) {
            println()
            println("Выберите новый system prompt:")
            println("1 - Свободный режим (без system prompt)")
            println("2 - Логические задачи (SYSTEM_FORMAT_PROMPT_LOGIC)")
            println("3 - Токарь (SYSTEM_FORMAT_PROMPT_TECH)")
            println("4 - Пират 18 века (SYSTEM_FORMAT_PROMPT_TECH)")
            print("Ваш выбор (1/2/3): ")

            val choice = console.readLine("")?.trim()

            currentSystemPrompt = when (choice) {
                "1" -> SYSTEM_FORMAT_PROMPT
                "2" -> SYSTEM_FORMAT_PROMPT_LOGIC
                "3" -> SYSTEM_FORMAT_PROMPT_TOKAR
                "4" -> SYSTEM_FORMAT_PROMPT_PIRATE
                else -> {
                    println("Неизвестный выбор, оставляю прежний system prompt.")
                    currentSystemPrompt
                }
            }
            val role = when (currentSystemPrompt) {
                SYSTEM_FORMAT_PROMPT -> "Обычный ИИ помощник"
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

            // Используем streaming для получения ответа
            var finalAnswer: LlmAnswer? = null

            // Собираем полный ответ, показывая индикатор загрузки
            print("⏳ ")
            System.out.flush()

            sendMessageUseCase.stream(
                conversationWithSystem,
                currentTemperature,
                currentMaxTokens,
            ).collect { result ->
                when (result) {
                    is StreamResult.TextChunk -> {
                        // Тихо накапливаем ответ (не печатаем сырой TOON)
                    }
                    is StreamResult.Complete -> {
                        finalAnswer = result.answer
                    }
                }
            }

            // Очищаем индикатор загрузки и выводим результат
            print("\r")  // Возврат каретки для затирания индикатора

            finalAnswer?.let { answer ->
                // Определяем что выводить: document (если phase=ready) или message
                val textToDisplay = if (answer.phase == "ready" && answer.document.isNotBlank()) {
                    answer.document
                } else {
                    answer.message
                }

                // Плавный вывод текста посимвольно (эффект печатной машинки)
                for (char in textToDisplay) {
                    print(char)
                    System.out.flush()
                    // Небольшая задержка для эффекта печатания (2мс на символ)
                    delay(2)
                }

                println()
                println()

                // Сохраняем ответ в историю разговора
                conversation += LlmMessage(
                    role = ChatRole.ASSISTANT,
                    content = answer.message
                )

                // Отображение статистики токенов
                printTokenStats(answer)
            }
        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }
}

private fun printTokenStats(answer: LlmAnswer) {
    val inputTokens = answer.inputTokens
    val outputTokens = answer.outputTokens
    val stopReason = answer.stopReason

    if (inputTokens == null && outputTokens == null && stopReason == null) {
        return
    }

    println("─".repeat(60))
    println("📊 Статистика токенов:")

    if (inputTokens != null) {
        println("   Input tokens (запрос):  $inputTokens")
    }
    if (outputTokens != null) {
        println("   Output tokens (ответ):  $outputTokens")
    }
    if (inputTokens != null && outputTokens != null) {
        println("   Всего токенов:          ${inputTokens + outputTokens}")
    }
    if (inputTokens != null && outputTokens != null) {
        val inputCost = inputTokens * 0.003 / 1000  // $3 per MTok
        val outputCost = outputTokens * 0.015 / 1000  // $15 per MTok
        val totalCost = inputCost + outputCost

        println("   Стоимость запроса: $${"%.6f".format(totalCost)}")
    }

    if (stopReason != null) {
        val reasonDescription = when (stopReason) {
            "end_turn" -> "✓ Модель завершила ответ естественно"
            "max_tokens" -> "⚠️ Ответ обрезан - достигнут лимит max_tokens!"
            "stop_sequence" -> "Остановлено по стоп-последовательности"
            else -> stopReason
        }
        println("   Stop reason:            $reasonDescription")
    }

    println("─".repeat(60))
    println()
}