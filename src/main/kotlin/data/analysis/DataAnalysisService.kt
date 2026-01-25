package org.example.data.analysis

import org.example.utils.SYSTEM_PROMPT_DATA_ANALYSIS
import java.nio.file.Path

/**
 * Сервис оркестрации анализа данных.
 * Связывает LLM с инструментами анализа.
 */
class DataAnalysisService(workingDir: Path) {

    private val tool = DataAnalysisTool(workingDir)

    companion object {
        // Паттерны для определения запросов на анализ
        private val FILE_PATTERNS = listOf(
            """\b\w+\.(csv|json|log|txt)\b""".toRegex(RegexOption.IGNORE_CASE),
            """(анализ|статистик|ошибк|данн)""".toRegex(RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Обрабатывает вызов инструмента от модели
     */
    fun handleToolCall(toolName: String, arguments: Map<String, Any?>): String {
        return when (toolName) {
            "analyze_file" -> {
                val path = arguments["path"]?.toString() ?: return "Ошибка: параметр path обязателен"
                tool.analyzeFile(path)
            }
            "execute_kotlin" -> {
                val code = arguments["code"]?.toString() ?: return "Ошибка: параметр code обязателен"
                tool.executeKotlin(code)
            }
            "format_result" -> {
                val data = arguments["data"] ?: return "Ошибка: параметр data обязателен"
                val format = arguments["format"]?.toString() ?: "text"
                tool.formatResult(data, format)
            }
            else -> "Неизвестный инструмент: $toolName"
        }
    }

    /**
     * Определяет, является ли запрос аналитическим
     * (упоминает файлы или аналитические термины)
     */
    fun isAnalysisQuery(query: String): Boolean {
        return FILE_PATTERNS.any { it.containsMatchIn(query) }
    }

    /**
     * Возвращает системный промпт для режима анализа
     */
    fun getSystemPrompt(): String = SYSTEM_PROMPT_DATA_ANALYSIS

    /**
     * Возвращает определения инструментов для модели
     */
    fun getToolDefinitions() = tool.getToolDefinitions()
}
