package org.example.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.data.converter.ToonConverter
import org.example.domain.models.LlmMessage
import org.example.data.dto.LlmRequest
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer

/**
 * Результат стриминга на уровне репозитория
 */
sealed class StreamResult {
    /** Текстовый фрагмент для отображения */
    data class TextChunk(val text: String) : StreamResult()

    /** Финальный ответ с распарсенными данными */
    data class Complete(val answer: LlmAnswer) : StreamResult()
}

interface ChatRepository {

    suspend fun send(
        conversation: List<LlmMessage>,
        maxTokens: Int = 1024,
        temperature: Double? = null,
    ): List<LlmAnswer>

    /**
     * Отправить запрос первому клиенту и получить стрим ответа.
     * Используется для streaming-режима (один клиент).
     */
    fun sendStream(
        conversation: List<LlmMessage>,
        maxTokens: Int = 1024,
        temperature: Double? = null,
    ): Flow<StreamResult>
}

class ChatRepositoryImpl(
    private val clients: List<LlmClient>,
    private val defaultMaxTokens: Int = 2048,
    private val defaultTemperature: Double? = null,
) : ChatRepository {

    override suspend fun send(
        conversation: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double?
    ): List<LlmAnswer> = coroutineScope {
        val systemMessage = conversation.filter { it.role == ChatRole.SYSTEM }
        val dialogMessages = conversation.filter { it.role != ChatRole.SYSTEM }

        // Convert dialog messages to TOON format before sending
        val toonEncodedMessages = dialogMessages.map { message ->
            LlmMessage(
                role = message.role,
                content = ToonConverter.encodeUserMessage(message.content)
            )
        }

        clients.map { client ->
            async {
                val request = LlmRequest(
                    model = client.model,
                    messages = toonEncodedMessages,
                    systemPrompt = systemMessage.first().content,
                    maxTokens = maxTokens,
                    temperature = temperature ?: defaultTemperature
                )

                val response = client.send(request)

                // Use ToonConverter to parse TOON-formatted response
                val structured = ToonConverter.extractPayload(response.text)

                LlmAnswer(
                    model = response.model,
                    rawToon = response.text,
                    phase = structured.phase ?: "unknown",
                    document = structured.document.orEmpty(),
                    message = structured.message,
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    stopReason = response.stopReason,
                )
            }
        }.awaitAll()
    }

    override fun sendStream(
        conversation: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double?
    ): Flow<StreamResult> {
        val client = clients.first()

        val systemMessage = conversation.filter { it.role == ChatRole.SYSTEM }
        val dialogMessages = conversation.filter { it.role != ChatRole.SYSTEM }

        val toonEncodedMessages = dialogMessages.map { message ->
            LlmMessage(
                role = message.role,
                content = ToonConverter.encodeUserMessage(message.content)
            )
        }

        val request = LlmRequest(
            model = client.model,
            messages = toonEncodedMessages,
            systemPrompt = systemMessage.firstOrNull()?.content,
            maxTokens = maxTokens,
            temperature = temperature ?: defaultTemperature
        )

        return client.sendStream(request).map { event ->
            when (event) {
                is StreamEvent.TextDelta -> StreamResult.TextChunk(event.text)
                is StreamEvent.Complete -> {
                    val structured = ToonConverter.extractPayload(event.fullText)
                    StreamResult.Complete(
                        LlmAnswer(
                            model = event.model,
                            rawToon = event.fullText,
                            phase = structured.phase ?: "unknown",
                            document = structured.document.orEmpty(),
                            message = structured.message,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            stopReason = event.stopReason,
                        )
                    )
                }
            }
        }
    }
}