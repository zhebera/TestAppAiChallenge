package org.example.mcp.server.crm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модели данных для CRM системы
 */

@Serializable
data class UsersDatabase(
    val users: List<User> = emptyList()
)

@Serializable
data class TicketsDatabase(
    val tickets: List<Ticket> = emptyList()
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String,
    val phone: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    val subscription: Subscription? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class Subscription(
    val plan: String,
    @SerialName("expires_at")
    val expiresAt: String,
    val features: List<String> = emptyList()
)

@Serializable
data class Ticket(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val subject: String,
    val status: TicketStatus,
    val priority: TicketPriority,
    val category: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val messages: List<TicketMessage> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("assigned_to")
    val assignedTo: String? = null,
    val resolution: String? = null
)

@Serializable
enum class TicketStatus {
    @SerialName("open")
    OPEN,
    @SerialName("in_progress")
    IN_PROGRESS,
    @SerialName("resolved")
    RESOLVED,
    @SerialName("closed")
    CLOSED
}

@Serializable
enum class TicketPriority {
    @SerialName("low")
    LOW,
    @SerialName("medium")
    MEDIUM,
    @SerialName("high")
    HIGH,
    @SerialName("critical")
    CRITICAL
}

@Serializable
data class TicketMessage(
    val id: String,
    val role: String,  // "user", "support", "system"
    val content: String,
    val timestamp: String
)
