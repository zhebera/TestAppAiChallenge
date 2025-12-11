package org.example.data.network

/**
 * Интерфейс для клиента суммаризации.
 * Используется для сжатия истории диалога.
 */
interface SummaryClient {
    /**
     * Создать краткое резюме текста диалога.
     * @param dialogText Текст диалога для суммаризации
     * @return Краткое резюме
     */
    suspend fun summarize(dialogText: String): String
}
