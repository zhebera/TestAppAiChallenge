package org.example.app.commands

import org.example.data.dto.LlmRequest
import org.example.data.network.LlmClient
import org.example.data.rag.RagService
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage

/**
 * Команда /help - интеллектуальный помощник по кодбазе проекта.
 *
 * Использование:
 * - /help            - показать общую справку о командах
 * - /help <вопрос>   - задать вопрос о проекте (использует RAG + AI)
 */
class HelpCommand(
    private val ragService: RagService?,
    private val helpClient: LlmClient?
) : Command {

    override fun matches(input: String): Boolean {
        return input.startsWith("/help")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        val query = input.removePrefix("/help").trim()

        if (query.isEmpty()) {
            printGeneralHelp()
            return CommandResult.Continue
        }

        // Интеллектуальный поиск
        return processIntelligentHelp(query)
    }

    private fun printGeneralHelp() {
        println("""
            |=== Справка по командам ===
            |
            |Основные команды:
            |  exit                   - выход из приложения
            |  /new или /clear        - начать новый диалог
            |  /stats                 - показать статистику истории
            |  /help [вопрос]         - интеллектуальный помощник по проекту
            |
            |Настройки:
            |  /changePrompt          - изменить системный промпт
            |  /temperature <0.0-1.0> - изменить температуру генерации
            |  /maxTokens <число>     - изменить максимум токенов
            |
            |RAG (поиск по базе знаний):
            |  /rag status            - показать статус индекса
            |  /rag index-project     - проиндексировать файлы проекта
            |  /rag search <запрос>   - поиск по базе знаний
            |  /rag on/off            - включить/выключить автоматический RAG
            |
            |MCP серверы:
            |  /mcp list              - показать подключенные серверы
            |  /mcp connect <имя>     - подключить MCP сервер
            |
            |Память:
            |  /memory show           - показать последние сообщения
            |  /memory search <текст> - поиск в истории сообщений
            |  /memory clear          - очистить всю историю
            |
            |Интеллектуальная справка:
            |  /help как работает RAG               - объяснение с примерами кода
            |  /help как добавить новую команду     - паттерны и примеры
            |  /help стиль кодирования              - convention проекта
            |
            |Для получения подробной информации о конкретной теме,
            |используйте /help с вашим вопросом.
        """.trimMargin())
        println()
    }

    private suspend fun processIntelligentHelp(query: String): CommandResult {
        if (ragService == null || helpClient == null) {
            println("Интеллектуальная справка недоступна (RAG или helpClient не инициализированы)")
            println()
            return CommandResult.Continue
        }

        println("Поиск информации по запросу: $query")
        println()

        // 1. Поиск по RAG с фильтрацией только файлов проекта
        val ragContext = try {
            ragService.search(query, topK = 10, minSimilarity = 0.25f)
        } catch (e: Exception) {
            println("Ошибка поиска в RAG: ${e.message}")
            println()
            return CommandResult.Continue
        }

        // Фильтруем только файлы проекта (.kt, .md, .kts) - исключаем Wikipedia статьи
        val projectResults = ragContext.results.filter { result ->
            val sourceFile = result.chunk.sourceFile
            sourceFile.endsWith(".kt") ||
            sourceFile.endsWith(".md") ||
            sourceFile.endsWith(".kts") ||
            sourceFile.contains("/")  // Файлы проекта содержат пути
        }.take(5)

        if (projectResults.isEmpty()) {
            println("Не найдено релевантной информации в кодбазе проекта.")
            println("Попробуйте:")
            println("  1. Проиндексировать проект: /rag index-project")
            println("  2. Уточнить запрос или использовать другие ключевые слова")
            println()
            return CommandResult.Continue
        }

        // 2. Формируем system prompt для help
        val systemPrompt = """
        Ты - интеллектуальный ассистент по кодбазе проекта TestAppAiChallenge.

        Твоя задача:
        - Объяснить, как работает код на основе предоставленных фрагментов
        - Показать примеры использования из реального кода проекта
        - Указать архитектурные паттерны и стиль кодирования
        - Дать практические рекомендации по работе с кодбазой

        Формат ответа:
        1. Краткий ответ на вопрос (2-3 предложения)
        2. Детальное объяснение с примерами из найденных фрагментов
        3. Ссылки на файлы в формате `путь/к/файлу:строка`

        ВАЖНО:
        - Отвечай на русском языке
        - Используй ТОЛЬКО информацию из предоставленных фрагментов кода
        - Если информации недостаточно, честно скажи об этом
        - Приводи конкретные примеры кода из фрагментов
        """.trimIndent()

        // 3. Формируем запрос
        val userMessage = buildString {
            append("Вопрос пользователя: $query\n\n")
            append("Релевантные фрагменты кода:\n\n")
            projectResults.forEachIndexed { index, result ->
                append("=== Фрагмент ${index + 1}: ${result.chunk.sourceFile} ===\n")
                append(result.chunk.content)
                append("\n\n")
            }
            append("Ответь на вопрос пользователя, используя эти фрагменты кода.")
        }

        // 4. Отправляем в AI (Haiku для скорости)
        val messages = listOf<LlmMessage>(
            LlmMessage(ChatRole.USER, userMessage)
        )

        val request = LlmRequest(
            model = helpClient.model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = 2000,
            temperature = 0.3
        )

        println("=== Ответ ассистента ===\n")

        val response = try {
            helpClient.send(request)
        } catch (e: Exception) {
            println("Ошибка при генерации ответа: ${e.message}")
            println()
            return CommandResult.Continue
        }

        println(response.text)
        println()

        // 5. Показываем источники
        println("=== Источники ===")
        projectResults.forEach { result ->
            println("  - ${result.chunk.sourceFile}")
        }
        println()

        return CommandResult.Continue
    }
}
