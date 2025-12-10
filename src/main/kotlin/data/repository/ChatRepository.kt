package org.example.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val json: Json,
    private val defaultMaxTokens: Int = 2048,
    private val defaultTemperature: Double? = null,
) : ChatRepository {

    @Serializable
    private data class StructuredPayload(
        val phase: String? = null,
        val document: String? = null,
        val message: String,
    )

    override suspend fun send(
        conversation: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double?
    ): List<LlmAnswer> = coroutineScope {
        val systemMessage = conversation.filter { it.role == ChatRole.SYSTEM }
        val dialogMessages = conversation.filter { it.role != ChatRole.SYSTEM }

        clients.map { client ->
            async {
                val request = LlmRequest(
                    model = client.model,
                    messages = dialogMessages,
                    systemPrompt = systemMessage.first().content,
                    maxTokens = maxTokens,
                    temperature = temperature ?: defaultTemperature
                )

                val response = client.send(request)

                val cleanedText = response.text
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val structured = try {
                    json.decodeFromString<StructuredPayload>(cleanedText)
                } catch (_: Throwable) {
                    StructuredPayload(
                        phase = "unknown",
                        document = "",
                        message = cleanedText
                    )
                }

                LlmAnswer(
                    model = response.model,
                    rawJson = response.rawJson,
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