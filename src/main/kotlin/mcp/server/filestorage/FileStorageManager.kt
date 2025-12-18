package org.example.mcp.server.filestorage

import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Менеджер файлового хранилища
 * Управляет сохранением, чтением и списком файлов
 */
class FileStorageManager(
    private val baseDir: String = "./saved_files"
) {
    private val storageDir = File(baseDir)

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    /**
     * Сохранение контента в файл
     * @param content содержимое для сохранения
     * @param filename имя файла (опционально, будет сгенерировано автоматически)
     * @param format формат файла: "txt", "md", "json"
     * @return результат сохранения с информацией о файле
     */
    fun saveToFile(content: String, filename: String? = null, format: String = "md"): SaveResult {
        return try {
            val extension = when (format.lowercase()) {
                "json" -> "json"
                "txt" -> "txt"
                else -> "md"
            }

            val actualFilename = if (filename.isNullOrBlank()) {
                generateFilename(extension)
            } else {
                // Добавляем расширение если его нет
                if (filename.contains(".")) filename else "$filename.$extension"
            }

            // Санитизируем имя файла
            val safeFilename = actualFilename
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(100) // Ограничиваем длину

            val file = File(storageDir, safeFilename)
            file.writeText(content, Charsets.UTF_8)

            SaveResult(
                success = true,
                filename = safeFilename,
                absolutePath = file.absolutePath,
                size = content.length,
                message = "Файл успешно сохранён: ${file.absolutePath}"
            )
        } catch (e: Exception) {
            SaveResult(
                success = false,
                filename = "",
                absolutePath = "",
                error = "Ошибка сохранения: ${e.message}"
            )
        }
    }

    /**
     * Чтение файла
     */
    fun readFile(filename: String): ReadResult {
        return try {
            val file = File(storageDir, filename)
            if (!file.exists()) {
                return ReadResult(
                    success = false,
                    content = "",
                    error = "Файл не найден: $filename"
                )
            }

            if (!file.canonicalPath.startsWith(storageDir.canonicalPath)) {
                return ReadResult(
                    success = false,
                    content = "",
                    error = "Доступ запрещён: попытка выхода за пределы хранилища"
                )
            }

            ReadResult(
                success = true,
                content = file.readText(Charsets.UTF_8),
                filename = filename,
                absolutePath = file.absolutePath,
                size = file.length().toInt()
            )
        } catch (e: Exception) {
            ReadResult(
                success = false,
                content = "",
                error = "Ошибка чтения: ${e.message}"
            )
        }
    }

    /**
     * Список сохранённых файлов
     */
    fun listFiles(): ListResult {
        return try {
            val files = storageDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file ->
                    FileInfo(
                        filename = file.name,
                        absolutePath = file.absolutePath,
                        size = file.length().toInt(),
                        lastModified = formatDate(file.lastModified())
                    )
                }
                ?: emptyList()

            ListResult(
                success = true,
                files = files,
                totalCount = files.size,
                storageDir = storageDir.absolutePath
            )
        } catch (e: Exception) {
            ListResult(
                success = false,
                files = emptyList(),
                error = "Ошибка получения списка: ${e.message}"
            )
        }
    }

    /**
     * Удаление файла
     */
    fun deleteFile(filename: String): DeleteResult {
        return try {
            val file = File(storageDir, filename)
            if (!file.exists()) {
                return DeleteResult(
                    success = false,
                    error = "Файл не найден: $filename"
                )
            }

            if (!file.canonicalPath.startsWith(storageDir.canonicalPath)) {
                return DeleteResult(
                    success = false,
                    error = "Доступ запрещён"
                )
            }

            if (file.delete()) {
                DeleteResult(success = true, filename = filename)
            } else {
                DeleteResult(success = false, error = "Не удалось удалить файл")
            }
        } catch (e: Exception) {
            DeleteResult(success = false, error = "Ошибка удаления: ${e.message}")
        }
    }

    private fun generateFilename(extension: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        return "note_$timestamp.$extension"
    }

    private fun formatDate(timestamp: Long): String {
        val dateTime = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamp),
            java.time.ZoneId.systemDefault()
        )
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}

@Serializable
data class SaveResult(
    val success: Boolean,
    val filename: String,
    val absolutePath: String,
    val size: Int = 0,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class ReadResult(
    val success: Boolean,
    val content: String,
    val filename: String = "",
    val absolutePath: String = "",
    val size: Int = 0,
    val error: String? = null
)

@Serializable
data class ListResult(
    val success: Boolean,
    val files: List<FileInfo>,
    val totalCount: Int = 0,
    val storageDir: String = "",
    val error: String? = null
)

@Serializable
data class FileInfo(
    val filename: String,
    val absolutePath: String,
    val size: Int,
    val lastModified: String
)

@Serializable
data class DeleteResult(
    val success: Boolean,
    val filename: String = "",
    val error: String? = null
)