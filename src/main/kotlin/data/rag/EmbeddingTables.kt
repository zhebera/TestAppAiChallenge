package org.example.data.rag

import org.jetbrains.exposed.sql.Table

/**
 * Таблица для хранения чанков документов с их эмбеддингами.
 * Эмбеддинги хранятся как BLOB (байты FloatArray).
 */
object ChunksTable : Table("document_chunks") {
    val id = long("id").autoIncrement()
    val sourceFile = varchar("source_file", 255)  // Имя исходного файла
    val chunkIndex = integer("chunk_index")        // Порядковый номер чанка в файле
    val content = text("content")                  // Текст чанка
    val embedding = blob("embedding")              // Вектор эмбеддинга (BLOB)
    val embeddingModel = varchar("embedding_model", 100)  // Модель для генерации
    val createdAt = long("created_at")             // Unix timestamp создания

    override val primaryKey = PrimaryKey(id)

    init {
        // Индекс для быстрого поиска по файлу
        index(false, sourceFile)
    }
}

/**
 * Таблица метаданных индекса.
 * Хранит информацию о последней индексации.
 */
object IndexMetadataTable : Table("index_metadata") {
    val id = long("id").autoIncrement()
    val key = varchar("key", 100).uniqueIndex()
    val value = text("value")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}