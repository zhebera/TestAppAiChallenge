package org.example.data.analysis

/**
 * Типы поддерживаемых файлов для анализа
 */
enum class FileType {
    CSV, JSON, LOG, UNKNOWN;

    companion object {
        fun fromExtension(ext: String): FileType = when (ext.lowercase()) {
            "csv" -> CSV
            "json" -> JSON
            "log", "txt" -> LOG
            else -> UNKNOWN
        }
    }
}

/**
 * Информация о колонке (для CSV)
 */
data class ColumnInfo(
    val name: String,
    val inferredType: String  // "Int", "Double", "String", "Boolean"
)

/**
 * Схема файла — результат анализа структуры
 */
data class FileSchema(
    val type: FileType,
    val filePath: String,
    val columns: List<ColumnInfo>? = null,  // для CSV
    val jsonStructure: String? = null,       // для JSON (описание структуры)
    val sampleData: String,                  // первые N строк
    val totalLines: Int,
    val fileSizeBytes: Long
)

/**
 * Контекст для выполнения кода
 */
data class AnalysisContext(
    val filePath: String,
    val fileContent: String,
    val schema: FileSchema
)

/**
 * Результат выполнения кода
 */
sealed class ExecutionResult {
    data class Success(
        val output: Any,
        val executionTimeMs: Long
    ) : ExecutionResult()

    data class Error(
        val message: String,
        val stackTrace: String? = null
    ) : ExecutionResult()
}

/**
 * Результат форматирования для вывода
 */
data class FormattedResult(
    val text: String,
    val data: Any? = null,  // структурированные данные (таблица, JSON)
    val dataFormat: String = "text"  // "text", "table", "json"
)
