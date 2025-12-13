package org.example.data.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Таблица для хранения всех сообщений чата (вопросы и ответы).
 * Хранит полную историю для персистентности между запусками.
 */
object MessagesTable : Table("messages") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 36)  // UUID сессии
    val role = varchar("role", 20)  // USER, ASSISTANT, SYSTEM
    val content = text("content")
    val timestamp = long("timestamp")  // Unix timestamp в миллисекундах
    val isCompressed = bool("is_compressed").default(false)  // Было ли сообщение сжато

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица для хранения сжатых (суммаризированных) сообщений.
 * Хранит summary предыдущих диалогов.
 */
object CompressedHistoryTable : Table("compressed_history") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 36)
    val summary = text("summary")  // Текст суммаризации
    val messagesCount = integer("messages_count")  // Сколько сообщений было сжато
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица для хранения метаданных сессий.
 */
object SessionsTable : Table("sessions") {
    val id = varchar("id", 36)  // UUID
    val createdAt = long("created_at")
    val lastActiveAt = long("last_active_at")
    val systemPrompt = text("system_prompt").nullable()

    override val primaryKey = PrimaryKey(id)
}
