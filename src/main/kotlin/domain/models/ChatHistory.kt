package org.example.domain.models

/**
 * Модель для управления историей чата с поддержкой сжатия.
 *
 * @property messages Полный список сообщений в истории
 * @property compressionThreshold Порог количества сообщений для запуска сжатия (по умолчанию 10)
 */
class ChatHistory(
    private val compressionThreshold: Int = 10,
) {
    private val _messages = mutableListOf<LlmMessage>()
    private var _summary: String? = null
    private var _compressedCount: Int = 0

    /** Все сообщения в истории (включая summary если есть) */
    val messages: List<LlmMessage>
        get() = buildMessageList()

    /** Количество сообщений до сжатия */
    val rawMessageCount: Int
        get() = _messages.size

    /** Текущее summary (если было сжатие) */
    val summary: String?
        get() = _summary

    /** Количество сообщений, которые были сжаты в summary */
    val compressedCount: Int
        get() = _compressedCount

    /** Проверяет, нужно ли сжатие */
    fun needsCompression(): Boolean = _messages.size >= compressionThreshold

    /** Добавить сообщение в историю */
    fun addMessage(message: LlmMessage) {
        _messages.add(message)
    }

    /** Добавить сообщение с указанием роли и контента */
    fun addMessage(role: ChatRole, content: String) {
        _messages.add(LlmMessage(role = role, content = content))
    }

    /**
     * Применить сжатие: заменить старые сообщения на summary.
     * @param summaryText Текст summary от LLM
     * @param keepRecentCount Сколько последних сообщений сохранить без сжатия
     */
    fun applySummary(summaryText: String, keepRecentCount: Int = 4) {
        if (_messages.size <= keepRecentCount) return

        val toCompress = _messages.size - keepRecentCount
        _compressedCount += toCompress

        // Сохраняем последние keepRecentCount сообщений
        val recentMessages = _messages.takeLast(keepRecentCount)

        // Обновляем summary
        _summary = if (_summary != null) {
            // Объединяем старое summary с новым
            "$_summary\n\n$summaryText"
        } else {
            summaryText
        }

        // Очищаем и добавляем только недавние сообщения
        _messages.clear()
        _messages.addAll(recentMessages)
    }

    /** Получить сообщения для сжатия (все кроме последних keepRecentCount) */
    fun getMessagesToCompress(keepRecentCount: Int = 4): List<LlmMessage> {
        if (_messages.size <= keepRecentCount) return emptyList()
        return _messages.dropLast(keepRecentCount)
    }

    /** Очистить всю историю */
    fun clear() {
        _messages.clear()
        _summary = null
        _compressedCount = 0
    }

    /** Построить список сообщений для отправки в LLM */
    private fun buildMessageList(): List<LlmMessage> {
        return if (_summary != null) {
            // Если есть summary, добавляем его как системное сообщение о контексте
            val summaryMessage = LlmMessage(
                role = ChatRole.USER,
                content = "[КРАТКОЕ СОДЕРЖАНИЕ ПРЕДЫДУЩЕГО ДИАЛОГА]\n$_summary\n[КОНЕЦ КРАТКОГО СОДЕРЖАНИЯ]"
            )
            val summaryAck = LlmMessage(
                role = ChatRole.ASSISTANT,
                content = "Понял, я учитываю контекст предыдущего диалога."
            )
            listOf(summaryMessage, summaryAck) + _messages
        } else {
            _messages.toList()
        }
    }

    /** Получить статистику истории */
    fun getStats(): HistoryStats {
        return HistoryStats(
            currentMessageCount = _messages.size,
            compressedMessageCount = _compressedCount,
            hasSummary = _summary != null,
            summaryLength = _summary?.length ?: 0,
            summaryText = summary,
        )
    }
}

/**
 * Статистика истории чата
 */
data class HistoryStats(
    val currentMessageCount: Int,
    val compressedMessageCount: Int,
    val hasSummary: Boolean,
    val summaryLength: Int,
    val summaryText: String?,
) {
    val totalProcessedMessages: Int
        get() = currentMessageCount + compressedMessageCount
}