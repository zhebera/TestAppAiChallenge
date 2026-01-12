package org.example.data.rag

import java.io.File

/**
 * Сканер файлов проекта для индексации в RAG.
 * Рекурсивно обходит директории проекта и фильтрует файлы по расширениям.
 */
class ProjectFileScanner(
    private val projectRoot: File,
    private val includedExtensions: Set<String> = setOf("kt", "md", "kts"),
    private val excludedDirs: Set<String> = setOf(
        "build", ".gradle", ".git", ".idea", "out", "target",
        "node_modules", ".kotlin", "bin", "generated"
    )
) {

    /**
     * Сканирует проект и возвращает список файлов для индексации.
     *
     * @return Список файлов, подходящих для индексации
     */
    fun scanFiles(): List<File> {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return emptyList()
        }

        return scanDirectory(projectRoot)
            .sortedBy { it.absolutePath }
    }

    private fun scanDirectory(directory: File): List<File> {
        val result = mutableListOf<File>()

        try {
            directory.listFiles()?.forEach { file ->
                when {
                    // Пропускаем исключённые директории
                    file.isDirectory && file.name in excludedDirs -> {
                        // Skip
                    }
                    // Рекурсивно обходим поддиректории
                    file.isDirectory -> {
                        result.addAll(scanDirectory(file))
                    }
                    // Добавляем файлы с нужными расширениями
                    file.isFile && file.extension in includedExtensions -> {
                        result.add(file)
                    }
                }
            }
        } catch (e: SecurityException) {
            // Игнорируем директории без прав доступа
        }

        return result
    }

    /**
     * Получить относительный путь файла от корня проекта.
     *
     * @param file Файл
     * @return Относительный путь или абсолютный путь, если файл вне проекта
     */
    fun getRelativePath(file: File): String {
        return try {
            file.relativeTo(projectRoot).path
        } catch (e: IllegalArgumentException) {
            // Файл вне проекта - возвращаем абсолютный путь
            file.absolutePath
        }
    }

    /**
     * Статистика сканирования.
     */
    data class ScanStats(
        val totalFiles: Int,
        val byExtension: Map<String, Int>,
        val largestFiles: List<Pair<String, Long>>
    )

    /**
     * Получить статистику по файлам проекта.
     *
     * @return Статистика сканирования
     */
    fun getScanStats(): ScanStats {
        val files = scanFiles()

        val byExtension = files.groupingBy { it.extension }.eachCount()

        val largestFiles = files
            .map { getRelativePath(it) to it.length() }
            .sortedByDescending { it.second }
            .take(10)

        return ScanStats(
            totalFiles = files.size,
            byExtension = byExtension,
            largestFiles = largestFiles
        )
    }
}
