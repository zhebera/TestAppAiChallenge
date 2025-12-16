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
 * LLM клиент с поддержкой инструментов через MCP
 * Оборачивает запросы к Claude API с инструментами и обрабатывает tool_use ответы
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

        // Цикл для обработки вызовов инструментов
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

            // Проверяем наличие tool_use в ответе
            val toolUseBlocks = dto.content.filter { it.type == "tool_use" }

            if (toolUseBlocks.isEmpty() || dto.stopReason != "tool_use") {
                // Нет вызовов инструментов, возвращаем финальный ответ
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

            // Обрабатываем вызовы инструментов
            println("\n[Обнаружен вызов инструмента: ${toolUseBlocks.map { it.name }}]")

            // Добавляем сообщение ассистента с блоками tool_use
            currentMessages.add(
                AnthropicMessageDto(
                    role = "assistant",
                    content = AnthropicMessageContent.Blocks(dto.content)
                )
            )

            // Выполняем инструменты и добавляем результаты
            val toolResults = toolUseBlocks.map { toolBlock ->
                val result = toolHandler.executeTool(toolBlock)
                println("[Результат ${toolBlock.name}: ${result.take(100)}...]")

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
        // Для простоты используем не-стриминг режим для запросов с инструментами
        // (стриминг с инструментами сложнее в реализации)
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