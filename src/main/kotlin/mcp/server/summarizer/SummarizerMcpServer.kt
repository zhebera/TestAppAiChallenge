package org.example.mcp.server.summarizer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * MCP сервер для суммаризации текста
 *
 * Инструменты:
 * - summarize_text: создание краткого изложения текста
 */
class SummarizerMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val summarizer = TextSummarizer()

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
            "notifications/initialized" -> JsonObject(emptyMap()) // No response for notifications
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
                    put("name", "summarizer-mcp")
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
                    addJsonObject {
                        put("name", "summarize_text")
                        put("description", "Создание краткого изложения (саммари) переданного текста. Использует извлекающий алгоритм суммаризации для выбора ключевых предложений.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("text") {
                                    put("type", "string")
                                    put("description", "Текст для суммаризации")
                                }
                                putJsonObject("max_sentences") {
                                    put("type", "integer")
                                    put("description", "Максимальное количество предложений в саммари (по умолчанию 5)")
                                    put("default", 5)
                                }
                                putJsonObject("style") {
                                    put("type", "string")
                                    put("description", "Стиль саммари: 'brief' (краткий текст), 'detailed' (разделённые абзацы), 'bullet' (маркированный список)")
                                    put("default", "brief")
                                    putJsonArray("enum") {
                                        add("brief")
                                        add("detailed")
                                        add("bullet")
                                    }
                                }
                            }
                            putJsonArray("required") { add("text") }
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
            "summarize_text" -> summarizeText(arguments)
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

    private fun summarizeText(arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'text' обязателен"
        val maxSentences = arguments["max_sentences"]?.jsonPrimitive?.intOrNull ?: 5
        val style = arguments["style"]?.jsonPrimitive?.content ?: "brief"

        val result = summarizer.summarize(text, maxSentences, style)

        if (!result.success) {
            return "Ошибка суммаризации: ${result.error}"
        }

        return buildString {
            appendLine("## Саммари")
            appendLine()
            appendLine(result.summary)
            appendLine()
            appendLine("---")
            appendLine("Статистика: ${result.sentenceCount} предложений, сжатие до ${result.compressionRatio}% от оригинала")
        }
    }
}

fun main() {
    SummarizerMcpServer().run()
}