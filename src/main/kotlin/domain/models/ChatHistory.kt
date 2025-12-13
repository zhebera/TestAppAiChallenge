package org.example.domain.models

import org.example.data.persistence.MemoryRepository

/**
 * Модель для управления историей чата с поддержкой сжатия и персистентного хранения.
 *
 * @property compressionThreshold Порог количества сообщений для запуска сжатия (по умолчанию 10)
 * @property memoryRepository Репозиторий для сохранения в SQLite (опционально)
 * @property sessionId ID текущей сессии для персистентности
 */
class ChatHistory(
    private val compressionThreshold: Int = 12,  // Порог для сжатия (10 сообщений + 2 запас)
    private val keepRecentCount: Int = 10,       // Хранить 10 последних сообщений
    private val memoryRepository: MemoryRepository? = null,
    private var sessionId: String? = null,
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

    /** ID текущей сессии */
    val currentSessionId: String?
        get() = sessionId

    /**
     * Инициализирует сессию и загружает данные из БД.
     * @param createNew Создать новую сессию вместо продолжения предыдущей
     */
    fun initSession(createNew: Boolean = false): String {
        val repo = memoryRepository ?: return ""

        sessionId = if (createNew) {
            repo.createSession()
        } else {
            repo.getOrCreateCurrentSession()
        }

        // Загружаем последние сообщения из БД
        loadFromDatabase()

        return sessionId!!
    }

    /**
     * Загружает данные из базы данных для текущей сессии.
     */
    private fun loadFromDatabase() {
        val repo = memoryRepository ?: return
        val sid = sessionId ?: return

        // Загружаем summary
        _summary = repo.getCombinedSummary(sid)
        _compressedCount = repo.getCompressedMessagesCount(sid)

        // Загружаем последние 10 сообщений (5 вопросов + 5 ответов)
        val recentMessages = repo.getRecentMessages(sid, 10)
        _messages.clear()
        _messages.addAll(recentMessages.map { stored ->
            LlmMessage(role = stored.role, content = stored.content)
        })
    }

    /** Проверяет, нужно ли сжатие */
    fun needsCompression(): Boolean =
        _messages.size >= compressionThreshold

    /** Добавить сообщение в историю */
    fun addMessage(message: LlmMessage) {
        _messages.add(message)

        // Сохраняем в БД
        val repo = memoryRepository
        val sid = sessionId
        if (repo != null && sid != null) {
            repo.saveMessage(sid, message)
            repo.updateSessionActivity(sid)
        }
    }

    /** Добавить сообщение с указанием роли и контента */
    fun addMessage(role: ChatRole, content: String) {
        addMessage(LlmMessage(role = role, content = content))
    }

    /**
     * Применить сжатие: заменить старые сообщения на summary.
     * @param summaryText Текст summary от LLM
     */
    fun applySummary(summaryText: String) {
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

        // Сохраняем summary в БД
        val repo = memoryRepository
        val sid = sessionId
        if (repo != null && sid != null) {
            repo.saveSummary(sid, summaryText, toCompress)

            // Помечаем старые сообщения как сжатые
            val allMessages = repo.getAllMessages(sid)
            val toMarkIds = allMessages
                .filter { !it.isCompressed }
                .dropLast(this.keepRecentCount)
                .map { it.id }
            if (toMarkIds.isNotEmpty()) {
                repo.markMessagesAsCompressed(toMarkIds)
            }
        }

        // Очищаем и добавляем только недавние сообщения
        _messages.clear()
        _messages.addAll(recentMessages)
    }

    /** Получить сообщения для сжатия (все кроме последних keepRecentCount) */
    fun getMessagesToCompress(): List<LlmMessage> {
        if (_messages.size <= keepRecentCount) return emptyList()
        return _messages.dropLast(keepRecentCount)
    }

    /** Очистить всю историю (только в памяти, не в БД) */
    fun clear() {
        _messages.clear()
        _summary = null
        _compressedCount = 0
    }

    /** Очистить историю и создать новую сессию */
    fun clearAndNewSession(): String {
        clear()
        return initSession(createNew = true)
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

    /** Поиск в истории сообщений */
    fun search(query: String): List<LlmMessage> {
        val repo = memoryRepository
        val sid = sessionId

        return if (repo != null && sid != null) {
            repo.searchMessages(sid, query).map { stored ->
                LlmMessage(role = stored.role, content = stored.content)
            }
        } else {
            // Поиск в памяти
            _messages.filter { it.content.contains(query, ignoreCase = true) }
        }
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