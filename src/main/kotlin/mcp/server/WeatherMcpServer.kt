package org.example.mcp.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MCP Server for Weather data
 * Communicates via stdio (stdin/stdout) using JSON-RPC 2.0
 */
fun main() {
    val server = WeatherMcpServer()
    server.run()
}

class WeatherMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val weatherApi = WeatherApi()

    private val tools = listOf(
        McpToolDefinition(
            name = "get_weather",
            description = "Get current weather for a city. Returns temperature, wind speed, and weather conditions.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") {
                        put("type", "string")
                        put("description", "City name (e.g., 'Moscow', 'New York', 'Tokyo')")
                    }
                }
                putJsonArray("required") {
                    add("city")
                }
            }
        ),
        McpToolDefinition(
            name = "get_forecast",
            description = "Get weather forecast for multiple days. Returns daily temperature (min/max), precipitation, wind, and conditions.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") {
                        put("type", "string")
                        put("description", "City name (e.g., 'Moscow', 'New York', 'Tokyo')")
                    }
                    putJsonObject("days") {
                        put("type", "integer")
                        put("description", "Number of days to forecast (1-16, default: 7)")
                        put("minimum", 1)
                        put("maximum", 16)
                    }
                }
                putJsonArray("required") {
                    add("city")
                }
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
                // Send error response
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
                put("name", "weather-mcp-server")
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
        val arguments = params?.get("arguments")?.jsonObject

        val city = arguments?.get("city")?.jsonPrimitive?.content
        if (city.isNullOrBlank()) {
            return JsonRpcResponseServer(
                id = request.id,
                result = buildToolErrorResult("City parameter is required")
            )
        }

        val toolResult = when (toolName) {
            "get_weather" -> getWeatherForCity(city)
            "get_forecast" -> {
                val days = arguments["days"]?.jsonPrimitive?.intOrNull ?: 7
                getForecastForCity(city, days)
            }
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
            put("isError", toolResult.startsWith("Error"))
        }

        return JsonRpcResponseServer(id = request.id, result = result)
    }

    private fun getWeatherForCity(city: String): String {
        return when (val geoResult = weatherApi.geocodeCity(city)) {
            is GeocodingResult.Success -> {
                when (val weatherResult = weatherApi.getCurrentWeather(geoResult.latitude, geoResult.longitude)) {
                    is WeatherResult.Success -> {
                        buildString {
                            appendLine("Current weather for ${geoResult.name}, ${geoResult.country ?: ""}:")
                            appendLine("Temperature: ${weatherResult.temperature}°C")
                            appendLine("Conditions: ${weatherResult.weatherDescription}")
                            appendLine("Wind: ${weatherResult.windSpeed} km/h (direction: ${weatherResult.windDirection}°)")
                            appendLine("Time of observation: ${weatherResult.time}")
                            append("Day/Night: ${if (weatherResult.isDay) "Day" else "Night"}")
                        }
                    }
                    is WeatherResult.Error -> "Error: ${weatherResult.message}"
                }
            }
            is GeocodingResult.NotFound -> "Error: ${geoResult.message}"
            is GeocodingResult.Error -> "Error: ${geoResult.message}"
        }
    }

    private fun getForecastForCity(city: String, days: Int): String {
        return when (val geoResult = weatherApi.geocodeCity(city)) {
            is GeocodingResult.Success -> {
                when (val forecastResult = weatherApi.getForecast(geoResult.latitude, geoResult.longitude, days)) {
                    is ForecastResult.Success -> {
                        buildString {
                            appendLine("Weather forecast for ${geoResult.name}, ${geoResult.country ?: ""} (${forecastResult.days.size} days):")
                            appendLine()
                            forecastResult.days.forEach { day ->
                                appendLine("${day.date}:")
                                appendLine("  Temperature: ${day.tempMin}°C - ${day.tempMax}°C")
                                appendLine("  Conditions: ${day.weatherDescription}")
                                appendLine("  Precipitation: ${day.precipitation} mm")
                                appendLine("  Max wind: ${day.windSpeedMax} km/h")
                            }
                        }.trimEnd()
                    }
                    is ForecastResult.Error -> "Error: ${forecastResult.message}"
                }
            }
            is GeocodingResult.NotFound -> "Error: ${geoResult.message}"
            is GeocodingResult.Error -> "Error: ${geoResult.message}"
        }
    }

    private fun buildToolErrorResult(message: String): JsonObject {
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Error: $message")
                })
            }
            put("isError", true)
        }
    }
}

// Server-side DTOs (separate from client DTOs to avoid confusion)

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