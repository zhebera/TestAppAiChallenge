package org.example.data.rag

import java.io.File

/**
 * Результат разбивки документа на чанки.
 */
data class DocumentChunk(
    val sourceFile: String,     // Имя исходного файла
    val chunkIndex: Int,        // Номер чанка в файле
    val content: String,        // Текст чанка
    val startOffset: Int,       // Начальная позиция в оригинальном тексте
    val endOffset: Int          // Конечная позиция в оригинальном тексте
)

/**
 * Сервис для разбивки текста на чанки с перекрытием.
 * Использует семантическую разбивку по параграфам с учётом максимального размера.
 */
class ChunkingService(
    private val maxChunkSize: Int = 100,        // Максимальный размер чанка (для русского текста)
    private val overlapSize: Int = 20,          // Размер перекрытия между чанками
    private val minChunkSize: Int = 20          // Минимальный размер чанка
) {

    /**
     * Загрузить и разбить все .txt файлы из директории.
     */
    fun loadAndChunkDirectory(directory: File): List<DocumentChunk> {
        if (!directory.exists() || !directory.isDirectory) {
            throw IllegalArgumentException("Директория не существует: ${directory.absolutePath}")
        }

        return directory.listFiles { file -> file.extension == "txt" }
            ?.flatMap { file -> chunkFile(file) }
            ?: emptyList()
    }

    /**
     * Разбить один файл на чанки.
     */
    fun chunkFile(file: File): List<DocumentChunk> {
        val text = file.readText()
        return chunkText(text, file.name)
    }

    /**
     * Разбить текст на чанки с перекрытием.
     * Старается разбивать по границам параграфов и предложений.
     */
    fun chunkText(text: String, sourceName: String = "unknown"): List<DocumentChunk> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<DocumentChunk>()
        val cleanedText = text.trim()

        // Сначала разбиваем на параграфы
        val paragraphs = cleanedText.split(Regex("\n{2,}"))

        var currentChunk = StringBuilder()
        var currentStart = 0
        var chunkIndex = 0
        var globalOffset = 0

        for (paragraph in paragraphs) {
            val trimmedParagraph = paragraph.trim()
            if (trimmedParagraph.isEmpty()) {
                globalOffset += paragraph.length + 2  // +2 за \n\n
                continue
            }

            // Если параграф сам по себе больше maxChunkSize, разбиваем его
            if (trimmedParagraph.length > maxChunkSize) {
                // Сначала сохраняем текущий чанк если есть
                if (currentChunk.isNotEmpty()) {
                    chunks.add(createChunk(currentChunk.toString(), sourceName, chunkIndex++, currentStart, globalOffset))
                    currentChunk = StringBuilder()
                }

                // Разбиваем большой параграф по предложениям
                val subChunks = splitLargeParagraph(trimmedParagraph, sourceName, chunkIndex, globalOffset)
                chunks.addAll(subChunks)
                chunkIndex += subChunks.size
                globalOffset += trimmedParagraph.length + 2
                currentStart = globalOffset
                continue
            }

            // Если добавление параграфа превысит лимит
            if (currentChunk.length + trimmedParagraph.length + 1 > maxChunkSize) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(createChunk(currentChunk.toString(), sourceName, chunkIndex++, currentStart, globalOffset))

                    // Overlap: берём конец предыдущего чанка
                    val overlap = getOverlap(currentChunk.toString())
                    currentChunk = StringBuilder(overlap)
                    currentStart = globalOffset - overlap.length
                }
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n\n")
            }
            currentChunk.append(trimmedParagraph)
            globalOffset += trimmedParagraph.length + 2
        }

        // Добавляем последний чанк
        if (currentChunk.length >= minChunkSize) {
            chunks.add(createChunk(currentChunk.toString(), sourceName, chunkIndex, currentStart, globalOffset))
        } else if (currentChunk.isNotEmpty() && chunks.isNotEmpty()) {
            // Добавляем маленький остаток к предыдущему чанку
            val lastChunk = chunks.removeAt(chunks.lastIndex)
            chunks.add(lastChunk.copy(
                content = lastChunk.content + "\n\n" + currentChunk.toString(),
                endOffset = globalOffset
            ))
        } else if (currentChunk.isNotEmpty()) {
            // Единственный маленький чанк - всё равно добавляем
            chunks.add(createChunk(currentChunk.toString(), sourceName, chunkIndex, currentStart, globalOffset))
        }

        return chunks
    }

    /**
     * Разбить большой параграф по предложениям.
     */
    private fun splitLargeParagraph(
        paragraph: String,
        sourceName: String,
        startIndex: Int,
        globalOffset: Int
    ): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        // Разбиваем по предложениям (точка, !, ?, с пробелом после)
        val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))

        var currentChunk = StringBuilder()
        var chunkIndex = startIndex
        var localOffset = 0

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length + 1 > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(createChunk(
                    currentChunk.toString(),
                    sourceName,
                    chunkIndex++,
                    globalOffset + localOffset - currentChunk.length,
                    globalOffset + localOffset
                ))

                val overlap = getOverlap(currentChunk.toString())
                currentChunk = StringBuilder(overlap)
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(sentence)
            localOffset += sentence.length + 1
        }

        if (currentChunk.length >= minChunkSize) {
            chunks.add(createChunk(
                currentChunk.toString(),
                sourceName,
                chunkIndex,
                globalOffset + localOffset - currentChunk.length,
                globalOffset + localOffset
            ))
        }

        return chunks
    }

    /**
     * Получить overlap из конца текста (по границе предложения если возможно).
     */
    private fun getOverlap(text: String): String {
        if (text.length <= overlapSize) return text

        val candidate = text.takeLast(overlapSize)
        // Ищем начало предложения в overlap
        val sentenceStart = candidate.indexOfFirst { it == '.' || it == '!' || it == '?' }
        return if (sentenceStart > 0 && sentenceStart < candidate.length - 10) {
            candidate.substring(sentenceStart + 1).trimStart()
        } else {
            candidate
        }
    }

    private fun createChunk(content: String, sourceName: String, index: Int, start: Int, end: Int): DocumentChunk {
        return DocumentChunk(
            sourceFile = sourceName,
            chunkIndex = index,
            content = content.trim(),
            startOffset = start,
            endOffset = end
        )
    }
}