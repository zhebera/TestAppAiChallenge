package org.example.support.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Запрос к чату поддержки
 */
@Serializable
data class SupportChatRequest(
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("ticket_id")
    val ticketId: String? = null,
    val message: String,
    @SerialName("session_id")
    val sessionId: String? = null
)

/**
 * Ответ от чата поддержки
 */
@Serializable
data class SupportChatResponse(
    val response: String,
    val sources: List<SupportSource> = emptyList(),
    @SerialName("suggested_actions")
    val suggestedActions: List<SuggestedAction> = emptyList(),
    @SerialName("session_id")
    val sessionId: String? = null
)

/**
 * Источник информации в ответе
 */
@Serializable
data class SupportSource(
    val type: String, // "rag" или "crm"
    val file: String? = null,
    val relevance: Double? = null,
    @SerialName("ticket_id")
    val ticketId: String? = null
)

/**
 * Рекомендуемое действие
 */
@Serializable
data class SuggestedAction(
    val action: String,
    val description: String
)

/**
 * Запрос на создание тикета
 */
@Serializable
data class CreateTicketRequest(
    @SerialName("user_id")
    val userId: String,
    val subject: String,
    val category: String,
    val priority: String = "medium",
    val message: String
)

/**
 * Информация о тикете
 */
@Serializable
data class TicketInfo(
    val id: String,
    val subject: String,
    val status: String,
    val priority: String,
    @SerialName("user_id")
    val userId: String? = null,
    val category: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Информация о пользователе
 */
@Serializable
data class UserInfo(
    val name: String,
    val email: String,
    val plan: String? = null
)

/**
 * Статус здоровья API
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    @SerialName("rag_available")
    val ragAvailable: Boolean,
    @SerialName("crm_available")
    val crmAvailable: Boolean,
    @SerialName("indexed_files")
    val indexedFiles: Int = 0
)

/**
 * Ответ с ошибкой
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int? = null
)
