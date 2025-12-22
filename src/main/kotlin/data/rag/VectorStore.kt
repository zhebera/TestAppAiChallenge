package org.example.data.rag

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Результат поиска по векторному хранилищу.
 */
data class SearchResult(
    val chunk: StoredChunk,
    val similarity: Float  // Косинусное сходство (0..1)
)

/**
 * Хранимый чанк с эмбеддингом.
 */
data class StoredChunk(
    val id: Long,
    val sourceFile: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredChunk) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Векторное хранилище на базе SQLite.
 * Хранит чанки документов и их эмбеддинги, выполняет поиск по косинусному сходству.
 */
class VectorStore {

    /**
     * Сохранить чанк с эмбеддингом.
     */
    fun saveChunk(
        sourceFile: String,
        chunkIndex: Int,
        content: String,
        embedding: FloatArray,
        model: String
    ): Long = transaction {
        ChunksTable.insert {
            it[ChunksTable.sourceFile] = sourceFile
            it[ChunksTable.chunkIndex] = chunkIndex
            it[ChunksTable.content] = content
            it[ChunksTable.embedding] = ExposedBlob(floatArrayToBytes(embedding))
            it[embeddingModel] = model
            it[createdAt] = System.currentTimeMillis()
        }[ChunksTable.id]
    }

    /**
     * Сохранить множество чанков (батч).
     */
    fun saveChunks(
        chunks: List<DocumentChunk>,
        embeddings: List<FloatArray>,
        model: String
    ) = transaction {
        ChunksTable.batchInsert(chunks.zip(embeddings)) { (chunk, embedding) ->
            this[ChunksTable.sourceFile] = chunk.sourceFile
            this[ChunksTable.chunkIndex] = chunk.chunkIndex
            this[ChunksTable.content] = chunk.content
            this[ChunksTable.embedding] = ExposedBlob(floatArrayToBytes(embedding))
            this[ChunksTable.embeddingModel] = model
            this[ChunksTable.createdAt] = System.currentTimeMillis()
        }
    }

    /**
     * Поиск похожих чанков по запросу.
     * @param queryEmbedding - эмбеддинг поискового запроса
     * @param topK - количество результатов
     * @param minSimilarity - минимальный порог сходства
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<SearchResult> = transaction {
        // Загружаем все чанки и вычисляем сходство
        // (для больших объёмов нужен более эффективный алгоритм, но для ~1000 чанков это ОК)
        val allChunks = ChunksTable.selectAll().map { row ->
            StoredChunk(
                id = row[ChunksTable.id],
                sourceFile = row[ChunksTable.sourceFile],
                chunkIndex = row[ChunksTable.chunkIndex],
                content = row[ChunksTable.content],
                embedding = bytesToFloatArray(row[ChunksTable.embedding].bytes)
            )
        }

        allChunks
            .map { chunk ->
                val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
                SearchResult(chunk, similarity)
            }
            .filter { it.similarity >= minSimilarity }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    /**
     * Получить количество сохранённых чанков.
     */
    fun getChunkCount(): Long = transaction {
        ChunksTable.selectAll().count()
    }

    /**
     * Получить список уникальных исходных файлов.
     */
    fun getIndexedFiles(): List<String> = transaction {
        ChunksTable
            .select(ChunksTable.sourceFile)
            .withDistinct()
            .map { it[ChunksTable.sourceFile] }
    }

    /**
     * Удалить все чанки определённого файла.
     */
    fun deleteFileChunks(sourceFile: String): Int = transaction {
        ChunksTable.deleteWhere { ChunksTable.sourceFile eq sourceFile }
    }

    /**
     * Очистить всё хранилище.
     */
    fun clearAll(): Int = transaction {
        ChunksTable.deleteAll()
    }

    /**
     * Проверить, проиндексирован ли файл.
     */
    fun isFileIndexed(sourceFile: String): Boolean = transaction {
        ChunksTable.selectAll().where { ChunksTable.sourceFile eq sourceFile }.count() > 0
    }

    /**
     * Сохранить метаданные индекса.
     */
    fun saveMetadata(key: String, value: String) = transaction {
        val existing = IndexMetadataTable.selectAll()
            .where { IndexMetadataTable.key eq key }
            .singleOrNull()

        if (existing != null) {
            IndexMetadataTable.update({ IndexMetadataTable.key eq key }) {
                it[IndexMetadataTable.value] = value
                it[updatedAt] = System.currentTimeMillis()
            }
        } else {
            IndexMetadataTable.insert {
                it[IndexMetadataTable.key] = key
                it[IndexMetadataTable.value] = value
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Получить метаданные индекса.
     */
    fun getMetadata(key: String): String? = transaction {
        IndexMetadataTable.selectAll()
            .where { IndexMetadataTable.key eq key }
            .singleOrNull()
            ?.get(IndexMetadataTable.value)
    }

    // === Вспомогательные функции ===

    /**
     * Косинусное сходство между двумя векторами.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    /**
     * Конвертация FloatArray в байты для хранения в BLOB.
     */
    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Конвертация байтов обратно в FloatArray.
     */
    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }
}