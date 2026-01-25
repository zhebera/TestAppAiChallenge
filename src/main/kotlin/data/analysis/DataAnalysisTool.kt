package org.example.data.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path

/**
 * Определение инструмента для модели
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * Главный инструмент для анализа данных — точка входа для LLM
 */
class DataAnalysisTool(private val workingDir: Path) {

    private val fileAnalyzer = FileAnalyzer(workingDir)
    private val kotlinExecutor = KotlinExecutor()

    // Текущий контекст анализа (устанавливается после analyzeFile)
    private var currentContext: AnalysisContext? = null

    /**
     * Анализирует файл и возвращает его схему
     */
    fun analyzeFile(path: String): String {
        val files = fileAnalyzer.findFile(path)

        if (files.isEmpty()) {
            return "Файл '$path' не найден в директории $workingDir"
        }

        val file = files.first()
        val schema = fileAnalyzer.analyzeStructure(file)
        val content = fileAnalyzer.readSample(file, 1000)

        // Сохраняем контекст для последующего выполнения кода
        currentContext = AnalysisContext(
            filePath = file.toString(),
            fileContent = content,
            schema = schema
        )

        return formatSchema(schema)
    }

    /**
     * Выполняет Kotlin-код для анализа данных
     */
    fun executeKotlin(code: String): String {
        val context = currentContext
            ?: return "Ошибка: сначала выполните analyze_file для загрузки данных"

        return when (val result = kotlinExecutor.execute(code, context)) {
            is ExecutionResult.Success -> {
                "Результат: ${result.output}\n(выполнено за ${result.executionTimeMs}мс)"
            }
            is ExecutionResult.Error -> {
                "Ошибка выполнения: ${result.message}"
            }
        }
    }

    /**
     * Форматирует результат для вывода
     */
    fun formatResult(data: Any, format: String = "text"): String {
        return when (format.lowercase()) {
            "table" -> formatAsTable(data)
            "json" -> formatAsJson(data)
            else -> data.toString()
        }
    }

    /**
     * Возвращает определения инструментов для LLM
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "analyze_file",
                description = "Анализирует файл (CSV, JSON, LOG) и возвращает его структуру: колонки, типы данных, примеры. Используй этот инструмент первым, чтобы понять структуру данных.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") {
                            put("type", "string")
                            put("description", "Имя файла или путь (например: sales.csv, logs/app.log)")
                        }
                    }
                    putJsonArray("required") { add("path") }
                }
            ),
            ToolDefinition(
                name = "execute_kotlin",
                description = "Выполняет Kotlin-код для анализа данных. Переменная 'data' содержит содержимое файла как строку. Используй после analyze_file.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("code") {
                            put("type", "string")
                            put("description", "Kotlin-код для выполнения. Доступна переменная 'data' с содержимым файла.")
                        }
                    }
                    putJsonArray("required") { add("code") }
                }
            ),
            ToolDefinition(
                name = "format_result",
                description = "Форматирует результат анализа для красивого вывода пользователю.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("data") {
                            put("type", "string")
                            put("description", "Данные для форматирования")
                        }
                        putJsonObject("format") {
                            put("type", "string")
                            put("description", "Формат вывода: text, table, json")
                            put("default", "text")
                        }
                    }
                    putJsonArray("required") { add("data") }
                }
            )
        )
    }

    private fun formatSchema(schema: FileSchema): String = buildString {
        appendLine("=== Анализ файла: ${schema.filePath} ===")
        appendLine("Тип: ${schema.type}")
        appendLine("Размер: ${schema.fileSizeBytes} байт")
        appendLine("Строк: ${schema.totalLines}")
        appendLine()

        when (schema.type) {
            FileType.CSV -> {
                appendLine("Колонки:")
                schema.columns?.forEach { col ->
                    appendLine("  - ${col.name}: ${col.inferredType}")
                }
            }
            FileType.JSON -> {
                appendLine("Структура JSON:")
                appendLine(schema.jsonStructure ?: "Не удалось определить")
            }
            else -> {}
        }

        appendLine()
        appendLine("Пример данных (первые строки):")
        appendLine("---")
        appendLine(schema.sampleData.take(500))
        if (schema.sampleData.length > 500) appendLine("...")
        appendLine("---")
    }

    private fun formatAsTable(data: Any): String = buildString {
        when (data) {
            is Map<*, *> -> {
                val maxKeyLen = data.keys.maxOfOrNull { it.toString().length } ?: 10
                appendLine("| ${"Key".padEnd(maxKeyLen)} | Value |")
                appendLine("|${"-".repeat(maxKeyLen + 2)}|-------|")
                data.forEach { (k, v) ->
                    appendLine("| ${k.toString().padEnd(maxKeyLen)} | $v |")
                }
            }
            is List<*> -> {
                data.forEachIndexed { i, item ->
                    appendLine("${i + 1}. $item")
                }
            }
            else -> append(data.toString())
        }
    }

    private fun formatAsJson(data: Any): String {
        return when (data) {
            is Map<*, *> -> {
                buildJsonObject {
                    data.forEach { (k, v) ->
                        put(k.toString(), v.toString())
                    }
                }.toString()
            }
            else -> "\"$data\""
        }
    }
}
