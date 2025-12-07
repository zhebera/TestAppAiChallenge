package org.example.data.network

import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse

/**
 * Унифицированный клиент для любой LLM
 */
interface LlmClient {
    val model: String
    val systemPrompt: String?

    suspend fun send(request: LlmRequest): LlmResponse
}