package org.example.domain.usecase

import org.example.data.network.SummaryClient
import org.example.domain.models.ChatHistory
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage

/**
 * Use case для сжатия истории диалога.
 * Использует бесплатный SummaryClient (OpenRouter) для создания summary.
 */
class CompressHistoryUseCase(
    private val summaryClient: SummaryClient
) {
    /**
     * Сжимает историю чата, если достигнут порог.
     * @return true если сжатие было выполнено, false если не требовалось
     */
    suspend fun compressIfNeeded(history: ChatHistory): Boolean {
        if (!history.needsCompression()) {
            return false
        }

        val messagesToCompress = history.getMessagesToCompress()
        if (messagesToCompress.isEmpty()) {
            return false
        }

        val dialogText = formatDialog(messagesToCompress)
        val summary = summaryClient.summarize(dialogText)
        history.applySummary(summary)

        return true
    }

    /**
     * Форматирует список сообщений в текст диалога.
     */
    private fun formatDialog(messages: List<LlmMessage>): String {
        return messages.joinToString("\n\n") { msg ->
            val roleLabel = when (msg.role) {
                ChatRole.USER -> "User"
                ChatRole.ASSISTANT -> "Assistant"
                ChatRole.SYSTEM -> "System"
            }
            "$roleLabel: ${msg.content}"
        }
    }
}
