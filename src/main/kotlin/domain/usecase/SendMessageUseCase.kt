package org.example.domain.usecase

import org.example.data.repository.ChatRepository
import org.example.domain.models.AssistantAnswer
import org.example.domain.models.ChatMessage

/**
 * UseCase, который отправляет историю диалога и получает ответ ассистента.
 */
class SendMessageUseCase(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        conversation: List<ChatMessage>
    ): AssistantAnswer = chatRepository.sendConversation(conversation)
}