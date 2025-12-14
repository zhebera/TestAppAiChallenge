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
    private val maxStoredMessages: Int = 10,     // Максимум сообщений в БД/памяти
    private val compressEvery: Int = 2,          // Сжимать каждые N сообщений (1 вопрос + 1 ответ)
    private val memoryRepository: MemoryRepository? = null,
    private var sessionId: String? = null,
) {
    private val _messages = mutableListOf<LlmMessage>()         // Все сообщения в памяти
    private val _summaries = mutableListOf<String>()            // Список summary (каждый для 2 сообщений)
    private var _totalCompressedCount: Int = 0                  // Общее количество сжатых сообщений
    private var _pendingForCompression: Int = 0                 // Сообщения, ожидающие сжатия

    /** Все сообщения в истории (включая summary если есть) */
    val messages: List<LlmMessage>
        get() = buildMessageList()

    /** Количество сообщений в памяти */
    val rawMessageCount: Int
        get() = _messages.size

    /** Объединённое summary */
    val summary: String?
        get() = if (_summaries.isEmpty()) null else _summaries.joinToString("\n")

    /** Количество сообщений, которые были сжаты */
    val compressedCount: Int
        get() = _totalCompressedCount

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

        // Загружаем все summaries
        _summaries.clear()
        val storedSummaries = repo.getSummaries(sid)
        _summaries.addAll(storedSummaries.map { it.summary })
        _totalCompressedCount = storedSummaries.sumOf { it.messagesCount }

        // Загружаем последние несжатые сообщения
        val recentMessages = repo.getRecentMessages(sid, maxStoredMessages)
        _messages.clear()
        _messages.addAll(recentMessages.map { stored ->
            LlmMessage(role = stored.role, content = stored.content)
        })

        // Несжатые сообщения из БД должны учитываться для последующего сжатия
        // Но они ещё не были сжаты в этой сессии, поэтому pending = количество загруженных сообщений
        // Однако, чтобы не дублировать сжатие, мы считаем их "уже обработанными"
        // и начинаем счёт pending с 0. Новые сообщения будут добавляться к pending.
        _pendingForCompression = 0
    }

    /**
     * Проверяет, нужно ли сжатие.
     * Сжатие нужно когда накопилось compressEvery новых сообщений.
     */
    fun needsCompression(): Boolean {
        return _pendingForCompression >= compressEvery
    }

    /** Добавить сообщение в историю */
    fun addMessage(message: LlmMessage) {
        _messages.add(message)
        _pendingForCompression++

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
     * Получить сообщения для сжатия.
     * Возвращает последние pending сообщений.
     */
    fun getMessagesToCompress(): List<LlmMessage> {
        if (_pendingForCompression <= 0) return emptyList()
        return _messages.takeLast(_pendingForCompression)
    }

    /**
     * Применить сжатие: добавить новый summary.
     * @param summaryText Текст summary от LLM
     */
    fun applySummary(summaryText: String) {
        if (_pendingForCompression <= 0) return

        val compressedCount = _pendingForCompression
        _totalCompressedCount += compressedCount

        // Добавляем новый summary в список
        _summaries.add(summaryText)

        // Сохраняем summary в БД
        val repo = memoryRepository
        val sid = sessionId
        if (repo != null && sid != null) {
            repo.saveSummary(sid, summaryText, compressedCount)
            // Сообщения НЕ помечаем как сжатые - они остаются в БД для загрузки
        }

        // Сбрасываем счётчик pending
        _pendingForCompression = 0

        // Проверяем лимит сообщений и удаляем старые
        trimOldData()
    }

    /**
     * Удаляет старые сообщения и их summaries когда превышен лимит.
     */
    private fun trimOldData() {
        if (_messages.size <= maxStoredMessages) return

        val toRemove = _messages.size - maxStoredMessages

        // Удаляем старые сообщения
        repeat(toRemove) { _messages.removeAt(0) }

        // Удаляем соответствующие summaries (каждый summary = compressEvery сообщений)
        val summariesToRemove = toRemove / compressEvery
        repeat(summariesToRemove.coerceAtMost(_summaries.size)) {
            _summaries.removeAt(0)
        }

        // Обновляем в БД (помечаем удалённые)
        val repo = memoryRepository
        val sid = sessionId
        if (repo != null && sid != null && summariesToRemove > 0) {
            // Можно добавить метод удаления старых summaries из БД
            // Пока оставим как есть - они останутся в БД, но не будут использоваться
        }
    }

    /** Очистить всю историю (только в памяти, не в БД) */
    fun clear() {
        _messages.clear()
        _summaries.clear()
        _totalCompressedCount = 0
        _pendingForCompression = 0
    }

    /**
     * Удаляет последнее сообщение из истории.
     * Используется для отката при ошибке запроса к LLM.
     */
    fun removeLastMessage() {
        if (_messages.isEmpty()) return

        val lastMessage = _messages.removeAt(_messages.size - 1)

        // Уменьшаем счётчик pending только если сообщение ещё не было сжато
        if (_pendingForCompression > 0) {
            _pendingForCompression--
        }

        // Удаляем из БД
        val repo = memoryRepository
        val sid = sessionId
        if (repo != null && sid != null) {
            repo.deleteLastMessage(sid)
        }
    }

    /** Очистить историю и создать новую сессию */
    fun clearAndNewSession(): String {
        clear()
        return initSession(createNew = true)
    }

    companion object {
        /** Максимум сообщений в контексте при отсутствии summary */
        private const val MAX_CONTEXT_MESSAGES = 6
    }

    /**
     * Построить список сообщений для отправки в LLM.
     * Формат: [summary контекст] + [все несжатые сообщения]
     * Или: [последние MAX_CONTEXT_MESSAGES сообщений] если summary нет
     */
    private fun buildMessageList(): List<LlmMessage> {
        val result = mutableListOf<LlmMessage>()

        // Добавляем summary если есть
        val combinedSummary = summary
        if (combinedSummary != null) {
            result.add(LlmMessage(
                role = ChatRole.USER,
                content = "[КОНТЕКСТ ДИАЛОГА]\n$combinedSummary\n[КОНЕЦ КОНТЕКСТА]"
            ))
            result.add(LlmMessage(
                role = ChatRole.ASSISTANT,
                content = "Понял контекст."
            ))
            // Добавляем все сообщения из памяти
            result.addAll(_messages)
        } else {
            // Нет summary - берём только последние MAX_CONTEXT_MESSAGES сообщений
            result.addAll(_messages.takeLast(MAX_CONTEXT_MESSAGES))
        }

        return result
    }

    /** Получить статистику истории */
    fun getStats(): HistoryStats {
        val combinedSummary = summary
        return HistoryStats(
            currentMessageCount = _messages.size,
            compressedMessageCount = _totalCompressedCount,
            hasSummary = _summaries.isNotEmpty(),
            summaryLength = combinedSummary?.length ?: 0,
            summaryText = combinedSummary,
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