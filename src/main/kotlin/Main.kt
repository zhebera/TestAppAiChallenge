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
import org.example.data.network.OpenRouterSummaryClient
import org.example.data.network.SummaryClient
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.MemoryRepository
import org.example.data.repository.ChatRepositoryImpl
import org.example.data.repository.StreamResult
import org.example.domain.models.ChatHistory
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage
import org.example.domain.usecase.CompressHistoryUseCase
import org.example.domain.usecase.SendMessageUseCase
import org.example.presentation.ConsoleInput
import org.example.utils.SYSTEM_FORMAT_PROMPT
import org.example.utils.SYSTEM_FORMAT_PROMPT_LOGIC
import org.example.utils.SYSTEM_FORMAT_PROMPT_PIRATE
import org.example.utils.SYSTEM_FORMAT_PROMPT_TOKAR
import java.text.SimpleDateFormat
import java.util.Date

// --- Константы и конфигурация ---
private const val CLAUDE_SONNET_MODEL_NAME = "claude-sonnet-4-20250514"
private const val CLAUDE_HAIKU_MODEL_NAME = "claude-haiku-4-5-20251001"
private const val CLAUDE_OPUS_MODEL_NAME = "claude-opus-4-1"

fun main() = runBlocking {
    // Инициализируем базу данных
    DatabaseConfig.init()
    val memoryRepository = MemoryRepository()

    ConsoleInput().use { console ->
        val anthropicKey = resolveApiKey(console, "ANTHROPIC_API_KEY", "Anthropic") ?: return@runBlocking
        val openRouterKey = resolveApiKey(console, "OPENROUTER_API_KEY", "OpenRouter (для сжатия истории)")

        val json = buildJsonConfig()
        val client = buildHttpClient(json)

        try {
            val useCases = buildUseCases(client, json, anthropicKey, openRouterKey)
            runChatLoop(console, useCases, memoryRepository)
        } finally {
            client.close()
        }
    }
}

private fun resolveApiKey(console: ConsoleInput, envVar: String, serviceName: String): String? {
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

private data class UseCases(
    val sendMessage: SendMessageUseCase,
    val compressHistory: CompressHistoryUseCase?  // null если нет OpenRouter ключа
)

private fun buildUseCases(
    client: HttpClient,
    json: Json,
    anthropicKey: String,
    openRouterKey: String?
): UseCases {
    val claudeSonnetClient = AnthropicClient(
        http = client,
        json = json,
        apiKey = anthropicKey,
        model = CLAUDE_SONNET_MODEL_NAME,
    )

    val clients: List<LlmClient> = listOf(claudeSonnetClient)

    val chatRepository = ChatRepositoryImpl(
        clients = clients,
    )

    // Создаём клиент для суммаризации (OpenRouter с бесплатной моделью)
    val summaryClient: SummaryClient? = openRouterKey?.let {
        OpenRouterSummaryClient(
            http = client,
            json = json,
            apiKey = it,
            primaryModel = "meta-llama/llama-3.2-3b-instruct:free"
        )
    }

    // CompressHistoryUseCase создаётся только если есть OpenRouter ключ
    val compressHistory = summaryClient?.let { CompressHistoryUseCase(it) }

    return UseCases(
        sendMessage = SendMessageUseCase(chatRepository),
        compressHistory = compressHistory
    )
}

private suspend fun runChatLoop(
    console: ConsoleInput,
    useCases: UseCases,
    memoryRepository: MemoryRepository
) {
    println("LLM Chat. Введите 'exit' для выхода.\n")
    println("Команды:")
    println("  /new или /clear - начать новый диалог (очистить историю)")
    println("  /stats          - показать статистику истории")
    println("  /changePrompt   - сменить System Prompt")
    println("  /temperature    - изменить temperature (0.0 - 1.0)")
    println("  /maxTokens      - изменить max_tokens")
    println("  /memory show    - показать последние сообщения из памяти")
    println("  /memory search  - поиск в истории сообщений")
    println("  /memory clear   - очистить всю память")
    println()


    var currentSystemPrompt: String = SYSTEM_FORMAT_PROMPT
    var currentTemperature: Double? = null
    var currentMaxTokens = 1024
    val chatHistory = ChatHistory(
        compressionThreshold = 12,  // Сжимать когда > 10 сообщений
        keepRecentCount = 10,       // Хранить 10 последних (5 вопросов + 5 ответов)
        memoryRepository = memoryRepository
    )

    // Инициализируем сессию и загружаем данные из БД
    val sessionId = chatHistory.initSession()
    val stats = chatHistory.getStats()
    if (stats.currentMessageCount > 0 || stats.compressedMessageCount > 0) {
        println("Восстановлена предыдущая сессия:")
        println("  Сообщений в памяти: ${stats.currentMessageCount}")
        if (stats.compressedMessageCount > 0) {
            println("  Сжатых сообщений:   ${stats.compressedMessageCount}")
        }
        println("  (используйте /new для начала нового диалога)")
        println()
    }

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

        // Команда /new или /clear - создать новую сессию
        if (text.equals("/new", ignoreCase = true) || text.equals("/clear", ignoreCase = true)) {
            val oldStats = chatHistory.getStats()
            val newSessionId = chatHistory.clearAndNewSession()
            println()
            println("Создана новая сессия.")
            if (oldStats.totalProcessedMessages > 0) {
                println("Предыдущая сессия сохранена в базе данных.")
                println("  Было сообщений: ${oldStats.currentMessageCount}")
                if (oldStats.compressedMessageCount > 0) {
                    println("  Сжатых: ${oldStats.compressedMessageCount}")
                }
            }
            println("Начинаем новый диалог.")
            println()
            continue
        }

        // Команда /stats - показать статистику истории
        if (text.equals("/stats", ignoreCase = true)) {
            val stats = chatHistory.getStats()
            println()
            println("─".repeat(50))
            println("Статистика истории диалога:")
            println("  Текущих сообщений в памяти: ${stats.currentMessageCount}")
            println("  Сжатых сообщений:           ${stats.compressedMessageCount}")
            println("  Всего обработано:           ${stats.totalProcessedMessages}")
            println("  Есть summary:               ${if (stats.hasSummary) "Да" else "Нет"}")
            if (stats.hasSummary) {
                println("  Размер summary:             ${stats.summaryLength} символов")
            }
            if (stats.hasSummary) {
                println("   Summary text: ${stats.summaryText}")
            }
            println("─".repeat(50))
            println()
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

        // Команды /memory
        if (text.startsWith("/memory", ignoreCase = true)) {
            val parts = text.split(" ", limit = 3)
            val subCommand = parts.getOrNull(1)?.lowercase() ?: "help"

            when (subCommand) {
                "show" -> {
                    println()
                    println("─".repeat(50))
                    println("Последние сообщения в памяти:")
                    println()

                    val sid = chatHistory.currentSessionId
                    if (sid != null) {
                        val messages = memoryRepository.getRecentMessages(sid, 10)
                        if (messages.isEmpty()) {
                            println("  (пусто)")
                        } else {
                            val dateFormat = SimpleDateFormat("dd.MM HH:mm")
                            messages.forEach { msg ->
                                val time = dateFormat.format(Date(msg.timestamp))
                                val role = when (msg.role) {
                                    ChatRole.USER -> "Вы"
                                    ChatRole.ASSISTANT -> "AI"
                                    ChatRole.SYSTEM -> "SYS"
                                }
                                val preview = msg.content.take(80).replace("\n", " ")
                                val suffix = if (msg.content.length > 80) "..." else ""
                                println("  [$time] $role: $preview$suffix")
                            }
                        }
                    } else {
                        println("  Сессия не инициализирована")
                    }
                    println("─".repeat(50))
                    println()
                }

                "search" -> {
                    val query = parts.getOrNull(2)
                    if (query.isNullOrBlank()) {
                        println("Использование: /memory search <запрос>")
                        println("Пример: /memory search kotlin")
                        println()
                    } else {
                        println()
                        println("─".repeat(50))
                        println("Поиск: \"$query\"")
                        println()

                        val results = chatHistory.search(query)
                        if (results.isEmpty()) {
                            println("  Ничего не найдено")
                        } else {
                            println("  Найдено ${results.size} сообщений:")
                            results.take(10).forEach { msg ->
                                val role = when (msg.role) {
                                    ChatRole.USER -> "Вы"
                                    ChatRole.ASSISTANT -> "AI"
                                    ChatRole.SYSTEM -> "SYS"
                                }
                                val preview = msg.content.take(100).replace("\n", " ")
                                val suffix = if (msg.content.length > 100) "..." else ""
                                println("  $role: $preview$suffix")
                                println()
                            }
                        }
                        println("─".repeat(50))
                        println()
                    }
                }

                "clear" -> {
                    println()
                    print("Вы уверены, что хотите очистить ВСЮ память? (yes/no): ")
                    val confirm = console.readLine("")?.trim()?.lowercase()
                    if (confirm == "yes" || confirm == "y") {
                        memoryRepository.clearAll()
                        chatHistory.clear()
                        val newSessionId = chatHistory.initSession(createNew = true)
                        println("Вся память очищена. Создана новая сессия.")
                    } else {
                        println("Отменено.")
                    }
                    println()
                }

                else -> {
                    println()
                    println("Команды /memory:")
                    println("  /memory show           - показать последние 10 сообщений")
                    println("  /memory search <текст> - поиск по истории сообщений")
                    println("  /memory clear          - очистить всю память (требует подтверждения)")
                    println()
                }
            }
            continue
        }

        if (text.equals("/changePrompt", ignoreCase = true)) {
            println()
            println("Выберите новый system prompt:")
            println("1 - Обычный ИИ помощник")
            println("2 - Логические задачи")
            println("3 - Токарь")
            println("4 - Пират 18 века")
            print("Ваш выбор (1/2/3/4): ")

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

        // Добавляем сообщение пользователя в историю
        chatHistory.addMessage(ChatRole.USER, text)

        try {
            // Сжатие истории (тихо, без сообщений)
            if (chatHistory.needsCompression() && useCases.compressHistory != null) {
                try {
                    useCases.compressHistory.compressIfNeeded(chatHistory)
                } catch (_: Exception) {
                    // Игнорируем ошибки сжатия
                }
            }

            // Строим список сообщений для отправки
            val conversationWithSystem: List<LlmMessage> =
                if (currentSystemPrompt.isNotBlank()) {
                    listOf(
                        LlmMessage(
                            role = ChatRole.SYSTEM,
                            content = currentSystemPrompt
                        )
                    ) + chatHistory.messages
                } else {
                    chatHistory.messages
                }

            // Используем streaming для получения отТепервета
            var finalAnswer: LlmAnswer? = null

            // Собираем полный ответ, показывая индикатор загрузки
            print("...")
            System.out.flush()

            useCases.sendMessage.stream(
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
                chatHistory.addMessage(ChatRole.ASSISTANT, answer.message)

                // Отображение статистики токенов
                printTokenStats(answer, chatHistory)
            }
        } catch (t: Throwable) {
            println()
            println("Ошибка при запросе: ${t.message}")
            println()
        }
    }
}

private fun printTokenStats(answer: LlmAnswer, chatHistory: ChatHistory) {
    val inputTokens = answer.inputTokens
    val outputTokens = answer.outputTokens
    val stopReason = answer.stopReason
    val stats = chatHistory.getStats()

    if (inputTokens == null && outputTokens == null && stopReason == null) {
        return
    }

    println("─".repeat(60))
    println("Статистика:")

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
            "end_turn" -> "Модель завершила ответ естественно"
            "max_tokens" -> "Ответ обрезан - достигнут лимит max_tokens!"
            "stop_sequence" -> "Остановлено по стоп-последовательности"
            else -> stopReason
        }
        println("   Stop reason:            $reasonDescription")
    }

    // Информация об истории
    println("   История: ${stats.currentMessageCount} сообщений" +
            if (stats.compressedMessageCount > 0) " (+${stats.compressedMessageCount} сжато)" else "")

    println("─".repeat(60))
    println()
}