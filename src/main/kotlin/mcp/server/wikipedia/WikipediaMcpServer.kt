package org.example.mcp.server.wikipedia

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * MCP сервер для поиска информации в Wikipedia
 *
 * Инструменты:
 * - search_wikipedia: поиск статей по запросу
 * - get_article: получение полного текста статьи
 */
class WikipediaMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val wikipediaApi = WikipediaApi()

    fun run() {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                println(json.encodeToString(response))
                System.out.flush()
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
            "notifications/initialized" -> return buildJsonObject {} // Notification, no response
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
                    put("name", "wikipedia-search-mcp")
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
                        put("name", "search_wikipedia")
                        put("description", "Поиск статей в Wikipedia по ключевым словам. Возвращает список статей с краткими описаниями.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "Поисковый запрос для поиска статей")
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put("description", "Максимальное количество результатов (по умолчанию 5)")
                                    put("default", 5)
                                }
                                putJsonObject("language") {
                                    put("type", "string")
                                    put("description", "Язык Wikipedia: 'ru' или 'en' (по умолчанию 'ru')")
                                    put("default", "ru")
                                }
                            }
                            putJsonArray("required") { add("query") }
                        }
                    }
                    addJsonObject {
                        put("name", "get_article")
                        put("description", "Получение полного текста статьи Wikipedia по её названию. Возвращает содержимое статьи и ссылку.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("title") {
                                    put("type", "string")
                                    put("description", "Точное название статьи в Wikipedia")
                                }
                                putJsonObject("language") {
                                    put("type", "string")
                                    put("description", "Язык Wikipedia: 'ru' или 'en' (по умолчанию 'ru')")
                                    put("default", "ru")
                                }
                            }
                            putJsonArray("required") { add("title") }
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
            "search_wikipedia" -> searchWikipedia(arguments)
            "get_article" -> getArticle(arguments)
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

    private fun searchWikipedia(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'query' обязателен"
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5
        val language = arguments["language"]?.jsonPrimitive?.content ?: "ru"

        val result = wikipediaApi.searchArticles(query, limit, language)

        if (!result.success) {
            return "Ошибка поиска: ${result.error}"
        }

        if (result.articles.isEmpty()) {
            return "По запросу '$query' статьи не найдены"
        }

        return buildString {
            appendLine("Результаты поиска по запросу '$query' (найдено: ${result.totalHits}):")
            appendLine()
            result.articles.forEachIndexed { index, article ->
                appendLine("${index + 1}. **${article.title}**")
                appendLine("   ${article.snippet}")
                appendLine()
            }
        }
    }

    private fun getArticle(arguments: JsonObject): String {
        val title = arguments["title"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'title' обязателен"
        val language = arguments["language"]?.jsonPrimitive?.content ?: "ru"

        val article = wikipediaApi.getArticle(title, language)

        if (!article.success) {
            return "Ошибка получения статьи: ${article.error}"
        }

        return buildString {
            appendLine("# ${article.title}")
            appendLine()
            if (article.url != null) {
                appendLine("Источник: ${article.url}")
                appendLine()
            }
            appendLine(article.content)
        }
    }
}

fun main() {
    WikipediaMcpServer().run()
}