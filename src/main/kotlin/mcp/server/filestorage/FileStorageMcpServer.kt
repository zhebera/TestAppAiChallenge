package org.example.mcp.server.filestorage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * MCP сервер для работы с файловым хранилищем
 *
 * Инструменты:
 * - save_to_file: сохранение текста в файл
 * - read_file: чтение файла
 * - list_files: список сохранённых файлов
 * - delete_file: удаление файла
 */
class FileStorageMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val storageManager = FileStorageManager()

    fun run() {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                if (response.isNotEmpty()) {
                    println(json.encodeToString(response))
                    System.out.flush()
                }
            } catch (e: Exception) {
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    }
                }
                println(json.encodeToString(errorResponse))
                System.out.flush()
            }
        }
    }

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> JsonObject(emptyMap())
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "filestorage-mcp")
                    put("version", "1.0.0")
                }
            }
        }
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    // save_to_file
                    addJsonObject {
                        put("name", "save_to_file")
                        put("description", "Сохранение текста в файл. Возвращает полный путь к сохранённому файлу и его имя. Используй этот инструмент когда пользователь просит сохранить информацию, заметку, статью или результат работы.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Текст для сохранения в файл")
                                }
                                putJsonObject("filename") {
                                    put("type", "string")
                                    put("description", "Имя файла (опционально, будет сгенерировано автоматически если не указано)")
                                }
                                putJsonObject("format") {
                                    put("type", "string")
                                    put("description", "Формат файла: 'md' (Markdown), 'txt' (текст), 'json'")
                                    put("default", "md")
                                    putJsonArray("enum") {
                                        add("md")
                                        add("txt")
                                        add("json")
                                    }
                                }
                            }
                            putJsonArray("required") { add("content") }
                        }
                    }

                    // read_file
                    addJsonObject {
                        put("name", "read_file")
                        put("description", "Чтение содержимого ранее сохранённого файла по имени.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("filename") {
                                    put("type", "string")
                                    put("description", "Имя файла для чтения")
                                }
                            }
                            putJsonArray("required") { add("filename") }
                        }
                    }

                    // list_files
                    addJsonObject {
                        put("name", "list_files")
                        put("description", "Получение списка всех сохранённых файлов с информацией о размере и дате изменения.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // delete_file
                    addJsonObject {
                        put("name", "delete_file")
                        put("description", "Удаление файла из хранилища по имени.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("filename") {
                                    put("type", "string")
                                    put("description", "Имя файла для удаления")
                                }
                            }
                            putJsonArray("required") { add("filename") }
                        }
                    }
                }
            }
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val result = when (toolName) {
            "save_to_file" -> saveToFile(arguments)
            "read_file" -> readFile(arguments)
            "list_files" -> listFiles()
            "delete_file" -> deleteFile(arguments)
            else -> "Неизвестный инструмент: $toolName"
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
    }

    private fun saveToFile(arguments: JsonObject): String {
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'content' обязателен"
        val filename = arguments["filename"]?.jsonPrimitive?.content
        val format = arguments["format"]?.jsonPrimitive?.content ?: "md"

        val result = storageManager.saveToFile(content, filename, format)

        return if (result.success) {
            buildString {
                appendLine("Файл успешно сохранён!")
                appendLine()
                appendLine("**Имя файла:** ${result.filename}")
                appendLine("**Полный путь:** ${result.absolutePath}")
                appendLine("**Размер:** ${result.size} символов")
                appendLine()
                appendLine("Для открытия файла выполните:")
                appendLine("```")
                appendLine("open \"${result.absolutePath}\"")
                appendLine("```")
            }
        } else {
            "Ошибка сохранения: ${result.error}"
        }
    }

    private fun readFile(arguments: JsonObject): String {
        val filename = arguments["filename"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'filename' обязателен"

        val result = storageManager.readFile(filename)

        return if (result.success) {
            buildString {
                appendLine("## Содержимое файла: ${result.filename}")
                appendLine()
                appendLine(result.content)
            }
        } else {
            "Ошибка чтения: ${result.error}"
        }
    }

    private fun listFiles(): String {
        val result = storageManager.listFiles()

        return if (result.success) {
            if (result.files.isEmpty()) {
                "Хранилище пусто. Файлы ещё не были сохранены.\n\nДиректория: ${result.storageDir}"
            } else {
                buildString {
                    appendLine("## Сохранённые файлы (${result.totalCount})")
                    appendLine()
                    appendLine("Директория: `${result.storageDir}`")
                    appendLine()
                    result.files.forEach { file ->
                        appendLine("- **${file.filename}** (${file.size} байт)")
                        appendLine("  Изменён: ${file.lastModified}")
                    }
                }
            }
        } else {
            "Ошибка: ${result.error}"
        }
    }

    private fun deleteFile(arguments: JsonObject): String {
        val filename = arguments["filename"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'filename' обязателен"

        val result = storageManager.deleteFile(filename)

        return if (result.success) {
            "Файл '${result.filename}' успешно удалён"
        } else {
            "Ошибка удаления: ${result.error}"
        }
    }
}

fun main() {
    FileStorageMcpServer().run()
}