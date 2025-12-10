package org.example.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.example.data.repository.ChatRepository
import org.example.data.repository.StreamResult
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage

class SendMessageUseCase(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(
        conversation: List<LlmMessage>,
        temperature: Double? = null,
        maxTokens: Int = 1024
    ): List<LlmAnswer> {
        return repository.send(conversation, maxTokens = maxTokens, temperature = temperature)
    }

    /**
     * Отправить сообщение и получить стрим ответа
     */
    fun stream(
        conversation: List<LlmMessage>,
        temperature: Double? = null,
        maxTokens: Int = 1024
    ): Flow<StreamResult> {
        return repository.sendStream(conversation, maxTokens = maxTokens, temperature = temperature)
    }
}