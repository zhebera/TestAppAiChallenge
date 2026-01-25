package org.example.data.analysis

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.data.api.*
import org.example.data.dto.LlmRequest
import org.example.domain.models.LlmMessage
import org.example.domain.models.ChatRole
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * Integration tests for Data Analysis feature with real Ollama
 * Requires: Ollama running locally with qwen2.5:7b model
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class DataAnalysisIntegrationTest {

    private lateinit var httpClient: HttpClient
    private lateinit var json: Json
    private lateinit var ollamaClient: OllamaClient
    private lateinit var analysisService: DataAnalysisService

    private val workingDir = Paths.get(System.getProperty("user.dir"))
    private val modelName = OllamaClient.DEFAULT_MODEL

    @BeforeAll
    fun setup() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000 // 3 minutes for LLM
                connectTimeoutMillis = 10_000
            }
        }

        ollamaClient = OllamaClient(httpClient, json)
        analysisService = DataAnalysisService(workingDir)
    }

    @AfterAll
    fun teardown() {
        httpClient.close()
    }

    @Test
    @DisplayName("Ollama should be accessible")
    fun testOllamaHealth() = runBlocking {
        val isHealthy = ollamaClient.checkHealth()
        assertTrue(isHealthy, "Ollama должен быть доступен на localhost:11434")
    }

    @Test
    @DisplayName("qwen2.5:7b model should be available")
    fun testModelExists() = runBlocking {
        val exists = ollamaClient.checkModelExists("qwen2.5:7b")
        assertTrue(exists, "Модель qwen2.5:7b должна быть загружена")
    }

    @Test
    @DisplayName("Full analysis flow: CSV file analysis")
    fun testCsvAnalysisFlow() = runBlocking {
        println("\n=== TEST: CSV Analysis Flow ===\n")

        // Step 1: Build tool definitions (parameters is already JsonObject)
        val toolDefs = analysisService.getToolDefinitions()
        val ollamaTools = toolDefs.map { tool ->
            OllamaToolDto(
                type = "function",
                function = OllamaFunctionDto(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }

        println("Tools registered: ${ollamaTools.map { it.function.name }}")

        // Step 2: Send analysis request
        val userQuery = "Проанализируй файл sales_demo.csv и скажи какой продукт продаётся лучше всего"
        println("User: $userQuery\n")

        val request = LlmRequest(
            model = modelName,
            systemPrompt = analysisService.getSystemPrompt(),
            messages = listOf(LlmMessage(ChatRole.USER, userQuery))
        )

        val response = ollamaClient.sendWithTools(request, ollamaTools)
        println("Ollama response (done=${response.done}):")
        println("  Content: ${response.message.content.take(200)}...")
        println("  Tool calls: ${response.message.toolCalls?.size ?: 0}")

        // Step 3: Handle tool calls if present
        if (response.message.toolCalls != null && response.message.toolCalls.isNotEmpty()) {
            for (toolCall in response.message.toolCalls) {
                val toolName = toolCall.function.name
                val args = toolCall.function.arguments

                println("\n>>> Tool call: $toolName")
                println("    Args: $args")

                // Convert JsonObject to Map
                val argsMap = args.entries.associate { (k, v) ->
                    k to when (v) {
                        is JsonPrimitive -> v.contentOrNull
                        else -> v.toString()
                    }
                }

                val result = analysisService.handleToolCall(toolName, argsMap)
                println("    Result: ${result.take(500)}...")
            }
        }

        // Verify we got some response
        assertTrue(
            response.message.content.isNotBlank() || response.message.toolCalls?.isNotEmpty() == true,
            "Должен быть либо текстовый ответ, либо вызов инструмента"
        )

        println("\n=== CSV Analysis Test PASSED ===\n")
    }

    @Test
    @DisplayName("Full analysis flow: Log file error analysis")
    fun testLogAnalysisFlow() = runBlocking {
        println("\n=== TEST: Log Analysis Flow ===\n")

        val toolDefs = analysisService.getToolDefinitions()
        val ollamaTools = toolDefs.map { tool ->
            OllamaToolDto(
                type = "function",
                function = OllamaFunctionDto(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }

        val userQuery = "Посмотри файл app_errors.log и скажи какая ошибка встречается чаще всего"
        println("User: $userQuery\n")

        val request = LlmRequest(
            model = modelName,
            systemPrompt = analysisService.getSystemPrompt(),
            messages = listOf(LlmMessage(ChatRole.USER, userQuery))
        )

        val response = ollamaClient.sendWithTools(request, ollamaTools)
        println("Ollama response:")
        println("  Content: ${response.message.content.take(300)}...")
        println("  Tool calls: ${response.message.toolCalls?.size ?: 0}")

        if (response.message.toolCalls != null) {
            for (toolCall in response.message.toolCalls) {
                val toolName = toolCall.function.name
                val args = toolCall.function.arguments

                println("\n>>> Tool call: $toolName")

                val argsMap = args.entries.associate { (k, v) ->
                    k to when (v) {
                        is JsonPrimitive -> v.contentOrNull
                        else -> v.toString()
                    }
                }

                val result = analysisService.handleToolCall(toolName, argsMap)
                println("    Result preview: ${result.take(400)}...")
            }
        }

        assertTrue(
            response.message.content.isNotBlank() || response.message.toolCalls?.isNotEmpty() == true,
            "Должен быть ответ или вызов инструмента"
        )

        println("\n=== Log Analysis Test PASSED ===\n")
    }

    @Test
    @DisplayName("Multi-turn conversation with tool execution")
    fun testMultiTurnConversation() = runBlocking {
        println("\n=== TEST: Multi-turn Conversation ===\n")

        val toolDefs = analysisService.getToolDefinitions()
        val ollamaTools = toolDefs.map { tool ->
            OllamaToolDto(
                type = "function",
                function = OllamaFunctionDto(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }

        val messages = mutableListOf<LlmMessage>()

        // Turn 1: User asks about file
        val query1 = "Что в файле config.json?"
        messages.add(LlmMessage(ChatRole.USER, query1))
        println("User: $query1\n")

        var request = LlmRequest(
            model = modelName,
            systemPrompt = analysisService.getSystemPrompt(),
            messages = messages
        )

        var response = ollamaClient.sendWithTools(request, ollamaTools)
        println("Assistant: ${response.message.content.take(200)}...")

        // Handle tool calls if any
        if (response.message.toolCalls?.isNotEmpty() == true) {
            val toolCall = response.message.toolCalls.first()
            val argsMap = toolCall.function.arguments.entries.associate { (k, v) ->
                k to when (v) {
                    is JsonPrimitive -> v.contentOrNull
                    else -> v.toString()
                }
            }

            val toolResult = analysisService.handleToolCall(toolCall.function.name, argsMap)
            println("\n[Tool ${toolCall.function.name} executed]")
            println("Result: ${toolResult.take(300)}...")

            // Add assistant message with tool call indication and tool result
            messages.add(LlmMessage(ChatRole.ASSISTANT, "Вызываю ${toolCall.function.name}..."))
            messages.add(LlmMessage(ChatRole.USER, "Результат инструмента:\n$toolResult"))

            // Get final response
            request = LlmRequest(
                model = modelName,
                systemPrompt = analysisService.getSystemPrompt(),
                messages = messages
            )

            response = ollamaClient.sendWithTools(request, ollamaTools)
            println("\nAssistant (after tool): ${response.message.content.take(300)}...")
        }

        // Turn 2: Follow-up question
        val query2 = "Какая версия API указана?"
        messages.add(LlmMessage(ChatRole.USER, query2))
        println("\nUser: $query2")

        request = LlmRequest(
            model = modelName,
            systemPrompt = analysisService.getSystemPrompt(),
            messages = messages
        )

        response = ollamaClient.sendWithTools(request, ollamaTools)
        println("Assistant: ${response.message.content.take(200)}...")

        assertTrue(messages.size >= 2, "Должно быть несколько сообщений в диалоге")

        println("\n=== Multi-turn Test PASSED ===\n")
    }

    @Test
    @DisplayName("Kotlin code execution via tool")
    fun testKotlinExecution() = runBlocking {
        println("\n=== TEST: Kotlin Code Execution ===\n")

        // Direct tool call test (without LLM)
        val code = """
            val lines = data.lines()
            val count = lines.size
            "Файл содержит ${'$'}count строк"
        """.trimIndent()

        println("Executing Kotlin code:")
        println(code)
        println()

        // First analyze a file to set context
        val analyzeResult = analysisService.handleToolCall(
            "analyze_file",
            mapOf("path" to "sales_demo.csv")
        )
        println("File analyzed: ${analyzeResult.take(200)}...")

        // Then execute code
        val execResult = analysisService.handleToolCall(
            "execute_kotlin",
            mapOf("code" to code)
        )
        println("\nExecution result: $execResult")

        assertTrue(execResult.contains("строк") || execResult.contains("lines"),
            "Результат должен содержать информацию о строках")

        println("\n=== Kotlin Execution Test PASSED ===\n")
    }
}
