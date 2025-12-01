package org.example.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicApi
import org.example.data.dto.AnthropicMessageDto
import org.example.domain.models.AssistantAnswer
import org.example.domain.models.ChatMessage

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

    override suspend fun sendConversation(
        messages: List<ChatMessage>
    ): AssistantAnswer {
        // Маппим доменные сообщения в DTO
        val dtoMessages = messages.map {
            AnthropicMessageDto(
                role = it.role,
                content = it.content
            )
        }

        val responseDto = api.sendMessages(
            model = modelName,
            messages = dtoMessages,
            maxTokens = 1024,
        )

        // Собираем текст ассистента из блоков
        val assistantText = responseDto.content
            .filter { it.type == "text" && it.text != null }
            .joinToString(separator = "") { it.text!! }

        val rawJson = json.encodeToString(responseDto)

        return AssistantAnswer(
            text = assistantText,
            model = responseDto.model.toString(),
            rawJson = rawJson
        )
    }
}