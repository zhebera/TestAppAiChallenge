package org.example.data.repository

import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicApi
import org.example.data.dto.AnthropicMessageDto
import org.example.domain.models.AssistantAnswer
import org.example.domain.models.ChatMessage
import org.example.domain.models.StructuredAnswer

interface ChatRepository {
    suspend fun sendConversation(
        messages: List<ChatMessage>
    ): AssistantAnswer
}

/**
 * Реализация ChatRepository через AnthropicApi.
 */
class AnthropicChatRepositoryImpl(
    private val api: AnthropicApi,
    private val json: Json,
    private val modelName: String,
) : ChatRepository {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override suspend fun sendConversation(
        messages: List<ChatMessage>
    ): AssistantAnswer {
        val systemPrompt = messages.firstOrNull { it.role == "system" }?.content

        val dtoMessages = messages
            .filter { it.role != "system" }
            .map {
                AnthropicMessageDto(
                    role = it.role,
                    content = it.content
                )
            }

        val responseDto = api.sendMessages(
            model = modelName,
            messages = dtoMessages,
            system = systemPrompt,
            maxTokens = 1024
        )

        val rawText = responseDto.content
            .filter { it.type == "text" && it.text != null }
            .joinToString(separator = "") { it.text ?: "" }

        val structured: StructuredAnswer = try {
            json.decodeFromString(StructuredAnswer.serializer(), rawText)
        } catch (t: Throwable) {
            StructuredAnswer(
                answer = rawText,
                details = "Модель вернула невалидный JSON по ожидаемому формату. Показан сырой текст.",
                language = "unknown"
            )
        }

        val formattedJson = prettyJson.encodeToString(
            StructuredAnswer.serializer(),
            structured
        )

        return AssistantAnswer(
            text = structured.answer,
            model = responseDto.model ?: "unknown",
            rawJson = formattedJson,
        )
    }
}