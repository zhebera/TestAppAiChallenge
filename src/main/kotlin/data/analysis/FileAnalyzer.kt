package org.example.data.analysis

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * Анализатор файлов — поиск и определение структуры данных
 */
class FileAnalyzer(private val workingDir: Path) {

    companion object {
        private const val DEFAULT_SAMPLE_LINES = 100
        private val EXCLUDED_DIRS = setOf("build", ".gradle", ".git", ".idea", "out", "target", "node_modules")
    }

    /**
     * Найти файлы по имени или паттерну
     */
    fun findFile(query: String): List<Path> {
        val results = mutableListOf<Path>()

        Files.walk(workingDir)
            .filter { path ->
                path.isRegularFile() &&
                !EXCLUDED_DIRS.any { excluded -> path.toString().contains("/$excluded/") }
            }
            .filter { path ->
                val fileName = path.name.lowercase()
                val queryLower = query.lowercase()
                fileName == queryLower || fileName.contains(queryLower)
            }
            .forEach { results.add(it) }

        return results.sortedBy { it.name }
    }

    /**
     * Проанализировать структуру файла
     */
    fun analyzeStructure(path: Path): FileSchema {
        val fileType = FileType.fromExtension(path.extension)
        val content = path.readText()
        val lines = content.lines()

        return when (fileType) {
            FileType.CSV -> analyzeCsv(path, content, lines)
            FileType.JSON -> analyzeJson(path, content, lines)
            else -> analyzeGeneric(path, content, lines, fileType)
        }
    }

    /**
     * Прочитать первые N строк файла
     */
    fun readSample(path: Path, lines: Int = DEFAULT_SAMPLE_LINES): String {
        return path.readLines().take(lines).joinToString("\n")
    }

    private fun analyzeCsv(path: Path, content: String, lines: List<String>): FileSchema {
        val headerLine = lines.firstOrNull() ?: ""
        val headers = headerLine.split(",").map { it.trim() }

        // Определяем типы по первым данным
        val dataLine = lines.getOrNull(1)?.split(",")?.map { it.trim() }
        val columns = headers.mapIndexed { index, header ->
            val sampleValue = dataLine?.getOrNull(index) ?: ""
            ColumnInfo(header, inferType(sampleValue))
        }

        return FileSchema(
            type = FileType.CSV,
            filePath = path.toString(),
            columns = columns,
            sampleData = lines.take(DEFAULT_SAMPLE_LINES).joinToString("\n"),
            totalLines = lines.size,
            fileSizeBytes = path.fileSize()
        )
    }

    private fun analyzeJson(path: Path, content: String, lines: List<String>): FileSchema {
        // Простой анализ структуры JSON
        val structure = buildString {
            if (content.trimStart().startsWith("[")) {
                appendLine("Array of objects")
            } else {
                appendLine("Object")
            }
            // Извлекаем ключи из первого объекта
            val keyPattern = """"(\w+)":\s*""".toRegex()
            val keys = keyPattern.findAll(content.take(500)).map { it.groupValues[1] }.distinct().toList()
            if (keys.isNotEmpty()) {
                appendLine("Keys: ${keys.joinToString(", ")}")
            }
        }

        return FileSchema(
            type = FileType.JSON,
            filePath = path.toString(),
            jsonStructure = structure,
            sampleData = content.take(2000),
            totalLines = lines.size,
            fileSizeBytes = path.fileSize()
        )
    }

    private fun analyzeGeneric(path: Path, content: String, lines: List<String>, type: FileType): FileSchema {
        return FileSchema(
            type = type,
            filePath = path.toString(),
            sampleData = lines.take(DEFAULT_SAMPLE_LINES).joinToString("\n"),
            totalLines = lines.size,
            fileSizeBytes = path.fileSize()
        )
    }

    private fun inferType(value: String): String {
        return when {
            value.toIntOrNull() != null -> "Int"
            value.toDoubleOrNull() != null -> "Double"
            value.lowercase() in listOf("true", "false") -> "Boolean"
            else -> "String"
        }
    }
}
