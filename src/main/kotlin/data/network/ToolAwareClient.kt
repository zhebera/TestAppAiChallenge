package org.example.data.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.data.api.*
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.data.mcp.ToolHandler
import org.example.domain.models.ChatRole

/**
 * LLM Client that supports tool use via MCP
 * Wraps requests to Claude API with tools and handles tool_use responses
 */
class ToolAwareClient(
    private val http: HttpClient,
    private val json: Json,
    private val apiKey: String,
    override val model: String,
    private val toolHandler: ToolHandler
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmResponse {
        val tools = toolHandler.getAvailableTools()
        var currentMessages = request.messages.map { msg ->
            AnthropicMessageDto(
                role = msg.role.name.lowercase(),
                content = AnthropicMessageContent.Text(msg.content)
            )
        }.toMutableList()

        var totalInputTokens = 0
        var totalOutputTokens = 0
        var lastModel = model
        var lastStopReason: String? = null

        // Loop to handle tool calls
        while (true) {
            val body = AnthropicRequestDto(
                model = model,
                system = request.systemPrompt,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                stream = false,
                messages = currentMessages,
                tools = tools.ifEmpty { null }
            )

            val httpResponse = http.post("https://api.anthropic.com/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(body)
            }

            val responseText = httpResponse.body<String>()
            val dto = json.decodeFromString(AnthropicResponseDto.serializer(), responseText)

            if (dto.type == "error") {
                throw IllegalStateException("Anthropic API error: $responseText")
            }

            totalInputTokens += dto.usage?.inputTokens ?: 0
            totalOutputTokens += dto.usage?.outputTokens ?: 0
            lastModel = dto.model ?: model
            lastStopReason = dto.stopReason

            // Check for tool_use in response
            val toolUseBlocks = dto.content.filter { it.type == "tool_use" }

            if (toolUseBlocks.isEmpty() || dto.stopReason != "tool_use") {
                // No tool calls, return final response
                val combinedText = dto.content
                    .filter { it.type == "text" }
                    .joinToString("") { it.text ?: "" }

                return LlmResponse(
                    model = lastModel,
                    text = combinedText.trim(),
                    rawJson = combinedText,
                    inputTokens = totalInputTokens,
                    outputTokens = totalOutputTokens,
                    stopReason = lastStopReason
                )
            }

            // Process tool calls
            println("\n[Tool call detected: ${toolUseBlocks.map { it.name }}]")

            // Add assistant message with tool_use blocks
            currentMessages.add(
                AnthropicMessageDto(
                    role = "assistant",
                    content = AnthropicMessageContent.Blocks(dto.content)
                )
            )

            // Execute tools and add results
            val toolResults = toolUseBlocks.map { toolBlock ->
                val result = toolHandler.executeTool(toolBlock)
                println("[Tool result for ${toolBlock.name}: ${result.take(100)}...]")

                AnthropicContentBlockDto(
                    type = "tool_result",
                    toolUseId = toolBlock.id,
                    content = result
                )
            }

            currentMessages.add(
                AnthropicMessageDto(
                    role = "user",
                    content = AnthropicMessageContent.Blocks(toolResults)
                )
            )
        }
    }

    override fun sendStream(request: LlmRequest): Flow<StreamEvent> = flow {
        // For simplicity, use non-streaming for tool-aware requests
        // (streaming with tools is more complex)
        val response = send(request)

        emit(StreamEvent.Complete(
            model = response.model,
            fullText = response.text,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            stopReason = response.stopReason
        ))
    }
}