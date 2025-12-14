package org.example.data.persistence

import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Данные о сохранённом сообщении.
 */
data class StoredMessage(
    val id: Long,
    val sessionId: String,
    val role: ChatRole,
    val content: String,
    val timestamp: Long,
    val isCompressed: Boolean
)

/**
 * Данные о сжатой истории.
 */
data class StoredSummary(
    val id: Long,
    val sessionId: String,
    val summary: String,
    val messagesCount: Int,
    val timestamp: Long
)

/**
 * Репозиторий для работы с памятью чата в SQLite.
 */
class MemoryRepository {

    companion object {
        private const val RECENT_MESSAGES_LIMIT = 10  // 5 вопросов + 5 ответов
    }

    /**
     * Создаёт новую сессию и возвращает её ID.
     */
    fun createSession(systemPrompt: String? = null): String = transaction {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        SessionsTable.insert {
            it[id] = sessionId
            it[createdAt] = now
            it[lastActiveAt] = now
            it[SessionsTable.systemPrompt] = systemPrompt
        }

        sessionId
    }

    /**
     * Получает последнюю активную сессию или создаёт новую.
     */
    fun getOrCreateCurrentSession(): String = transaction {
        val lastSession = SessionsTable
            .selectAll()
            .orderBy(SessionsTable.lastActiveAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        if (lastSession != null) {
            lastSession[SessionsTable.id]
        } else {
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            SessionsTable.insert {
                it[id] = sessionId
                it[createdAt] = now
                it[lastActiveAt] = now
            }
            sessionId
        }
    }

    /**
     * Обновляет время последней активности сессии.
     */
    fun updateSessionActivity(sessionId: String) = transaction {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[lastActiveAt] = System.currentTimeMillis()
        }
    }

    /**
     * Сохраняет сообщение в базу.
     */
    fun saveMessage(sessionId: String, message: LlmMessage): Long = transaction {
        val now = System.currentTimeMillis()

        MessagesTable.insert {
            it[MessagesTable.sessionId] = sessionId
            it[role] = message.role.name
            it[content] = message.content
            it[timestamp] = now
            it[isCompressed] = false
        } get MessagesTable.id
    }

    /**
     * Получает последние N сообщений сессии.
     */
    fun getRecentMessages(sessionId: String, limit: Int = RECENT_MESSAGES_LIMIT): List<StoredMessage> = transaction {
        MessagesTable
            .selectAll()
            .where { MessagesTable.sessionId eq sessionId }
            .orderBy(MessagesTable.timestamp, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                StoredMessage(
                    id = row[MessagesTable.id],
                    sessionId = row[MessagesTable.sessionId],
                    role = ChatRole.valueOf(row[MessagesTable.role]),
                    content = row[MessagesTable.content],
                    timestamp = row[MessagesTable.timestamp],
                    isCompressed = row[MessagesTable.isCompressed]
                )
            }
            .reversed()  // Возвращаем в хронологическом порядке
    }

    /**
     * Получает все сообщения сессии.
     */
    fun getAllMessages(sessionId: String): List<StoredMessage> = transaction {
        MessagesTable
            .selectAll()
            .where { MessagesTable.sessionId eq sessionId }
            .orderBy(MessagesTable.timestamp, SortOrder.ASC)
            .map { row ->
                StoredMessage(
                    id = row[MessagesTable.id],
                    sessionId = row[MessagesTable.sessionId],
                    role = ChatRole.valueOf(row[MessagesTable.role]),
                    content = row[MessagesTable.content],
                    timestamp = row[MessagesTable.timestamp],
                    isCompressed = row[MessagesTable.isCompressed]
                )
            }
    }

    /**
     * Помечает сообщения как сжатые.
     */
    fun markMessagesAsCompressed(messageIds: List<Long>) = transaction {
        MessagesTable.update({ MessagesTable.id inList messageIds }) {
            it[isCompressed] = true
        }
    }

    /**
     * Сохраняет сжатую историю (summary).
     */
    fun saveSummary(sessionId: String, summary: String, messagesCount: Int): Long = transaction {
        CompressedHistoryTable.insert {
            it[CompressedHistoryTable.sessionId] = sessionId
            it[CompressedHistoryTable.summary] = summary
            it[CompressedHistoryTable.messagesCount] = messagesCount
            it[timestamp] = System.currentTimeMillis()
        } get CompressedHistoryTable.id
    }

    /**
     * Получает все summary для сессии.
     */
    fun getSummaries(sessionId: String): List<StoredSummary> = transaction {
        CompressedHistoryTable
            .selectAll()
            .where { CompressedHistoryTable.sessionId eq sessionId }
            .orderBy(CompressedHistoryTable.timestamp, SortOrder.ASC)
            .map { row ->
                StoredSummary(
                    id = row[CompressedHistoryTable.id],
                    sessionId = row[CompressedHistoryTable.sessionId],
                    summary = row[CompressedHistoryTable.summary],
                    messagesCount = row[CompressedHistoryTable.messagesCount],
                    timestamp = row[CompressedHistoryTable.timestamp]
                )
            }
    }

    /**
     * Получает объединённый summary для сессии.
     */
    fun getCombinedSummary(sessionId: String): String? = transaction {
        val summaries = getSummaries(sessionId)
        if (summaries.isEmpty()) {
            null
        } else {
            summaries.joinToString("\n\n") { it.summary }
        }
    }

    /**
     * Получает общее количество сжатых сообщений.
     */
    fun getCompressedMessagesCount(sessionId: String): Int = transaction {
        CompressedHistoryTable
            .selectAll()
            .where { CompressedHistoryTable.sessionId eq sessionId }
            .sumOf { it[CompressedHistoryTable.messagesCount] }
    }

    /**
     * Поиск по содержимому сообщений.
     */
    fun searchMessages(sessionId: String, query: String): List<StoredMessage> = transaction {
        MessagesTable
            .selectAll()
            .where {
                (MessagesTable.sessionId eq sessionId) and
                        (MessagesTable.content.lowerCase() like "%${query.lowercase()}%")
            }
            .orderBy(MessagesTable.timestamp, SortOrder.DESC)
            .limit(20)
            .map { row ->
                StoredMessage(
                    id = row[MessagesTable.id],
                    sessionId = row[MessagesTable.sessionId],
                    role = ChatRole.valueOf(row[MessagesTable.role]),
                    content = row[MessagesTable.content],
                    timestamp = row[MessagesTable.timestamp],
                    isCompressed = row[MessagesTable.isCompressed]
                )
            }
    }

    /**
     * Удаляет последнее сообщение сессии.
     */
    fun deleteLastMessage(sessionId: String) = transaction {
        val lastMessage = MessagesTable
            .selectAll()
            .where { MessagesTable.sessionId eq sessionId }
            .orderBy(MessagesTable.timestamp, SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        if (lastMessage != null) {
            MessagesTable.deleteWhere { MessagesTable.id eq lastMessage[MessagesTable.id] }
        }
    }

    /**
     * Очищает все данные сессии.
     */
    fun clearSession(sessionId: String) = transaction {
        MessagesTable.deleteWhere { MessagesTable.sessionId eq sessionId }
        CompressedHistoryTable.deleteWhere { CompressedHistoryTable.sessionId eq sessionId }
        SessionsTable.deleteWhere { SessionsTable.id eq sessionId }
    }

    /**
     * Очищает все данные из базы.
     */
    fun clearAll() = transaction {
        MessagesTable.deleteAll()
        CompressedHistoryTable.deleteAll()
        SessionsTable.deleteAll()
    }

    /**
     * Получает статистику по памяти.
     */
    fun getMemoryStats(sessionId: String): MemoryStats = transaction {
        val totalMessages = MessagesTable
            .selectAll()
            .where { MessagesTable.sessionId eq sessionId }
            .count()

        val activeMessages = MessagesTable
            .selectAll()
            .where { (MessagesTable.sessionId eq sessionId) and (MessagesTable.isCompressed eq false) }
            .count()

        val summariesCount = CompressedHistoryTable
            .selectAll()
            .where { CompressedHistoryTable.sessionId eq sessionId }
            .count()

        val compressedCount = getCompressedMessagesCount(sessionId)

        MemoryStats(
            totalMessages = totalMessages.toInt(),
            activeMessages = activeMessages.toInt(),
            compressedMessages = compressedCount,
            summariesCount = summariesCount.toInt()
        )
    }

    /**
     * Получает список всех сессий.
     */
    fun getAllSessions(): List<SessionInfo> = transaction {
        SessionsTable
            .selectAll()
            .orderBy(SessionsTable.lastActiveAt, SortOrder.DESC)
            .map { row ->
                SessionInfo(
                    id = row[SessionsTable.id],
                    createdAt = row[SessionsTable.createdAt],
                    lastActiveAt = row[SessionsTable.lastActiveAt]
                )
            }
    }
}

data class MemoryStats(
    val totalMessages: Int,
    val activeMessages: Int,
    val compressedMessages: Int,
    val summariesCount: Int
)

data class SessionInfo(
    val id: String,
    val createdAt: Long,
    val lastActiveAt: Long
)
