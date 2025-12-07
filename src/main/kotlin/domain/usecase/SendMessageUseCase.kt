package org.example.domain.usecase

import org.example.data.repository.ChatRepository
import org.example.domain.models.LlmAnswer
import org.example.domain.models.LlmMessage

class SendMessageUseCase(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(
        conversation: List<LlmMessage>
    ): List<LlmAnswer> {
        return repository.send(conversation)
    }
}