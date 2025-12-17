package org.example.mcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MCP Сервер для футбольных новостей
 * Общается через stdio (stdin/stdout) по протоколу JSON-RPC 2.0
 */
fun main() {
    val server = FootballMcpServer()
    server.run()
}

class FootballMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val newsApi = FootballNewsApi()

    private val tools = listOf(
        McpToolDefinition(
            name = "get_football_news",
            description = """
                Получить последние футбольные новости из RSS фидов (ESPN, BBC Sport, Sky Sports).
                Возвращает новости о следующих лигах: АПЛ, Ла Лига, РПЛ, Серия А, Бундеслига, Лига 1.
                Новости фильтруются по релевантности к этим лигам.
            """.trimIndent(),
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Максимальное количество новостей для возврата (по умолчанию: 10)")
                        put("minimum", 1)
                        put("maximum", 50)
                    }
                    putJsonObject("league") {
                        put("type", "string")
                        put("description", "Фильтр по лиге (АПЛ, Ла Лига, РПЛ, Серия А, Бундеслига, Лига 1). Если не указано, возвращаются все.")
                    }
                }
                putJsonArray("required") { }
            }
        ),
        McpToolDefinition(
            name = "get_news_summary",
            description = """
                Получить краткую сводку последних футбольных новостей.
                Возвращает заголовки новостей с указанием источников.
            """.trimIndent(),
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("count") {
                        put("type", "integer")
                        put("description", "Количество новостей в сводке (по умолчанию: 5)")
                        put("minimum", 1)
                        put("maximum", 20)
                    }
                }
                putJsonArray("required") { }
            }
        )
    )

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.decodeFromString(JsonRpcRequestServer.serializer(), line)
                val response = handleRequest(request)
                println(json.encodeToString(JsonRpcResponseServer.serializer(), response))
                System.out.flush()
            } catch (e: Exception) {
                val errorResponse = JsonRpcResponseServer(
                    id = null,
                    error = JsonRpcErrorServer(
                        code = -32700,
                        message = "Parse error: ${e.message}"
                    )
                )
                println(json.encodeToString(JsonRpcResponseServer.serializer(), errorResponse))
                System.out.flush()
            }
        }
    }

    private fun handleRequest(request: JsonRpcRequestServer): JsonRpcResponseServer {
        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "notifications/initialized" -> JsonRpcResponseServer(id = request.id, result = JsonObject(emptyMap()))
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolsCall(request)
            else -> JsonRpcResponseServer(
                id = request.id,
                error = JsonRpcErrorServer(
                    code = -32601,
                    message = "Method not found: ${request.method}"
                )
            )
        }
    }

    private fun handleInitialize(request: JsonRpcRequestServer): JsonRpcResponseServer {
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", "football-news-mcp-server")
                put("version", "1.0.0")
            }
        }
        return JsonRpcResponseServer(id = request.id, result = result)
    }

    private fun handleToolsList(request: JsonRpcRequestServer): JsonRpcResponseServer {
        val result = buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(json.encodeToJsonElement(McpToolDefinition.serializer(), tool))
                }
            }
        }
        return JsonRpcResponseServer(id = request.id, result = result)
    }

    private fun handleToolsCall(request: JsonRpcRequestServer): JsonRpcResponseServer {
        val params = request.params
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val toolResult = when (toolName) {
            "get_football_news" -> getFootballNews(arguments)
            "get_news_summary" -> getNewsSummary(arguments)
            else -> return JsonRpcResponseServer(
                id = request.id,
                error = JsonRpcErrorServer(
                    code = -32602,
                    message = "Unknown tool: $toolName"
                )
            )
        }

        val result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", toolResult)
                })
            }
            put("isError", toolResult.startsWith("Ошибка"))
        }

        return JsonRpcResponseServer(id = request.id, result = result)
    }

    private fun getFootballNews(arguments: JsonObject): String {
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 10
        val leagueFilter = arguments["league"]?.jsonPrimitive?.contentOrNull

        return when (val result = newsApi.fetchLatestNews()) {
            is NewsResult.Success -> {
                var news = result.news

                // Фильтрация по лиге
                if (!leagueFilter.isNullOrBlank()) {
                    news = news.filter { it.leagues.contains(leagueFilter) }
                }

                // Сортировка по дате (новые первыми) и ограничение
                news = news.sortedByDescending { it.publishedAt }.take(limit)

                if (news.isEmpty()) {
                    return "Новостей не найдено" +
                        (if (!leagueFilter.isNullOrBlank()) " для лиги: $leagueFilter" else "")
                }

                buildString {
                    appendLine("Последние футбольные новости (${news.size}):")
                    appendLine()
                    news.forEachIndexed { index, item ->
                        appendLine("${index + 1}. ${item.title}")
                        if (item.description.isNotBlank() && item.description.length <= 200) {
                            appendLine("   ${item.description}")
                        }
                        appendLine("   Источник: ${item.sources}")
                        if (item.leagues.isNotEmpty()) {
                            appendLine("   Лиги: ${item.leagues.joinToString(", ")}")
                        }
                        appendLine("   Ссылка: ${item.link}")
                        appendLine()
                    }
                }.trimEnd()
            }
            is NewsResult.Error -> "Ошибка: ${result.message}"
        }
    }

    private fun getNewsSummary(arguments: JsonObject): String {
        val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 5

        return when (val result = newsApi.fetchLatestNews()) {
            is NewsResult.Success -> {
                val news = result.news.sortedByDescending { it.publishedAt }.take(count)

                if (news.isEmpty()) {
                    return "Новых футбольных новостей не найдено"
                }

                buildString {
                    appendLine("Сводка футбольных новостей:")
                    appendLine()
                    news.forEachIndexed { index, item ->
                        val leagues = if (item.leagues.isNotEmpty()) {
                            " [${item.leagues.joinToString(", ")}]"
                        } else ""
                        appendLine("${index + 1}. ${item.title}$leagues (${item.sources})")
                    }
                }.trimEnd()
            }
            is NewsResult.Error -> "Ошибка: ${result.message}"
        }
    }
}

// DTO для серверной стороны

@Serializable
data class JsonRpcRequestServer(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponseServer(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcErrorServer? = null
)

@Serializable
data class JsonRpcErrorServer(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null
)
