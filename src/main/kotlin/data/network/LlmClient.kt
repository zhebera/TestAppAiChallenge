package org.example.data.network

import kotlinx.coroutines.flow.Flow
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse

/**
 * Результат стриминга: либо текстовый chunk, либо финальный ответ с метаданными
 */
sealed class StreamEvent {
    /** Текстовый фрагмент для отображения */
    data class TextDelta(val text: String) : StreamEvent()

    /** Финальное событие с метаданными (токены, stop_reason) */
    data class Complete(
        val model: String,
        val fullText: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val stopReason: String?
    ) : StreamEvent()
}

/**
 * Унифицированный клиент для любой LLM
 */
interface LlmClient {
    val model: String

    /** Отправить запрос и получить полный ответ (блокирующий) */
    suspend fun send(request: LlmRequest): LlmResponse

    /** Отправить запрос и получить поток событий (стриминг) */
    fun sendStream(request: LlmRequest): Flow<StreamEvent>
}