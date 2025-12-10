package org.example.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.data.converter.ToonConverter
import org.example.domain.models.LlmMessage
import org.example.data.dto.LlmRequest
import org.example.data.network.LlmClient
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmAnswer

interface ChatRepository {

    suspend fun send(
        conversation: List<LlmMessage>,
        maxTokens: Int = 1024,
        temperature: Double? = null,
    ): List<LlmAnswer>
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
}