package org.example.data.scheduler

import kotlinx.coroutines.*
import org.example.data.persistence.FootballDatabaseUtils
import org.example.data.persistence.FootballRepository
import org.example.mcp.server.FootballNews
import org.example.mcp.server.FootballNewsApi
import org.example.mcp.server.NewsResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Планировщик для фоновой проверки футбольных новостей.
 * Работает 24/7, проверяет новости каждую минуту и выводит AI-сводку.
 */
class NewsScheduler(
    private val apiKey: String,
    private val intervalMs: Long = 60_000L  // 1 минута по умолчанию
) {
    private val newsApi = FootballNewsApi()
    private val repository = FootballRepository()
    private val aiProcessor = NewsAiProcessor(apiKey)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ANSI коды для зеленого цвета
    companion object {
        private const val GREEN = "\u001B[32m"
        private const val BRIGHT_GREEN = "\u001B[92m"
        private const val RESET = "\u001B[0m"
        private const val BOLD = "\u001B[1m"

        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    /**
     * Запустить планировщик в фоне.
     */
    fun start() {
        if (job?.isActive == true) {
            printGreen("Планировщик новостей уже запущен")
            return
        }

        // Очищаем данные футбольных новостей при старте
        FootballDatabaseUtils.clearAllData()

        printGreen("Запуск планировщика футбольных новостей (с AI-обработкой)...")
        printGreen("Проверка каждые ${intervalMs / 1000} секунд")
        printGreen("Лиги: АПЛ, Ла Лига, РПЛ, Серия А, Бундеслига, Лига 1")
        println()

        job = scope.launch {
            // Первая проверка сразу при запуске
            checkNews()

            // Затем проверяем с интервалом
            while (isActive) {
                delay(intervalMs)
                checkNews()
            }
        }
    }

    /**
     * Остановить планировщик.
     */
    fun stop() {
        job?.cancel()
        job = null
        printGreen("Планировщик новостей остановлен")
    }

    /**
     * Проверка на наличие новых новостей.
     */
    private suspend fun checkNews() {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = LocalDateTime.now().format(timeFormatter)

                when (val result = newsApi.fetchLatestNews()) {
                    is NewsResult.Success -> {
                        val newNews = result.news
                        val savedCount = repository.saveNewsList(newNews)

                        if (savedCount > 0) {
                            // Получаем необработанные новости для сводки
                            val unprocessedNews = repository.getUnprocessedNews()

                            // Обрабатываем через AI
                            printGreen("[$timestamp] Найдено ${unprocessedNews.size} новых новостей. Обрабатываю через AI...")

                            val aiSummary = aiProcessor.processNews(unprocessedNews)

                            if (aiSummary != null) {
                                printAiSummary(timestamp, aiSummary)
                            } else {
                                // Fallback если AI недоступен
                                printFallbackSummary(timestamp, unprocessedNews)
                            }

                            // Помечаем как обработанные
                            repository.markAsProcessed(unprocessedNews.map { it.id })
                        } else {
                            printNoNews(timestamp)
                        }
                    }
                    is NewsResult.Error -> {
                        printError(timestamp, result.message)
                    }
                }
            } catch (e: Exception) {
                val timestamp = LocalDateTime.now().format(timeFormatter)
                printError(timestamp, e.message ?: "Неизвестная ошибка")
            }
        }
    }

    /**
     * Вывод AI-сводки (зеленым цветом).
     */
    private fun printAiSummary(timestamp: String, summary: String) {
        println()
        printGreen("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        printGreenBold("        ФУТБОЛЬНЫЕ НОВОСТИ [$timestamp]")
        printGreen("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println()

        // Выводим каждую строку сводки зеленым
        summary.lines().forEach { line ->
            if (line.isNotBlank()) {
                printGreen("  $line")
            } else {
                println()
            }
        }

        println()
        printGreen("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println()
    }

    /**
     * Fallback вывод если AI недоступен.
     */
    private fun printFallbackSummary(timestamp: String, news: List<FootballNews>) {
        println()
        printGreenBold("[$timestamp] Новые футбольные новости (AI недоступен):")
        news.take(5).forEachIndexed { index, item ->
            printGreen("  ${index + 1}. ${item.title}")
        }
        println()
    }

    /**
     * Вывод сообщения об отсутствии новых новостей (зеленым цветом).
     */
    private fun printNoNews(timestamp: String) {
        printGreen("[$timestamp] Новых футбольных новостей не появилось")
    }

    /**
     * Вывод ошибки (зеленым цветом для консистентности).
     */
    private fun printError(timestamp: String, message: String) {
        printGreen("[$timestamp] Ошибка при проверке новостей: $message")
    }

    /**
     * Печать текста зеленым цветом.
     */
    private fun printGreen(text: String) {
        println("$GREEN$text$RESET")
    }

    /**
     * Печать текста ярко-зеленым жирным шрифтом.
     */
    private fun printGreenBold(text: String) {
        println("$BOLD$BRIGHT_GREEN$text$RESET")
    }

    /**
     * Проверить, запущен ли планировщик.
     */
    fun isRunning(): Boolean = job?.isActive == true

    /**
     * Получить статистику.
     */
    fun getStats(): NewsSchedulerStats {
        return NewsSchedulerStats(
            isRunning = isRunning(),
            totalNewsInDb = repository.getNewsCount(),
            intervalMs = intervalMs
        )
    }
}

/**
 * Статистика планировщика.
 */
data class NewsSchedulerStats(
    val isRunning: Boolean,
    val totalNewsInDb: Long,
    val intervalMs: Long
)
