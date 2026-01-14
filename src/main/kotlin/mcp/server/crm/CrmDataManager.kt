package org.example.mcp.server.crm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Менеджер для работы с CRM данными (JSON файлы)
 */
class CrmDataManager(
    private val dataDir: String = "data/crm"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val usersFile: File
        get() = File(dataDir, "users.json")

    private val ticketsFile: File
        get() = File(dataDir, "tickets.json")

    init {
        // Создаём директорию если не существует
        File(dataDir).mkdirs()

        // Создаём файлы с пустыми данными если не существуют
        if (!usersFile.exists()) {
            usersFile.writeText(json.encodeToString(UsersDatabase()))
        }
        if (!ticketsFile.exists()) {
            ticketsFile.writeText(json.encodeToString(TicketsDatabase()))
        }
    }

    // ==================== Users ====================

    fun getAllUsers(): List<User> {
        return try {
            json.decodeFromString<UsersDatabase>(usersFile.readText()).users
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getUserById(userId: String): User? {
        return getAllUsers().find { it.id == userId }
    }

    fun getUserByEmail(email: String): User? {
        return getAllUsers().find { it.email.equals(email, ignoreCase = true) }
    }

    fun createUser(user: User): User {
        val users = getAllUsers().toMutableList()
        users.add(user)
        saveUsers(users)
        return user
    }

    private fun saveUsers(users: List<User>) {
        usersFile.writeText(json.encodeToString(UsersDatabase(users)))
    }

    // ==================== Tickets ====================

    fun getAllTickets(): List<Ticket> {
        return try {
            json.decodeFromString<TicketsDatabase>(ticketsFile.readText()).tickets
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTicketById(ticketId: String): Ticket? {
        return getAllTickets().find { it.id == ticketId }
    }

    fun getTicketsByUserId(userId: String, status: TicketStatus? = null): List<Ticket> {
        return getAllTickets()
            .filter { it.userId == userId }
            .filter { status == null || it.status == status }
            .sortedByDescending { it.updatedAt }
    }

    fun searchTickets(
        query: String? = null,
        category: String? = null,
        status: TicketStatus? = null
    ): List<Ticket> {
        return getAllTickets()
            .filter { ticket ->
                val matchesQuery = query.isNullOrBlank() ||
                    ticket.subject.contains(query, ignoreCase = true) ||
                    ticket.messages.any { it.content.contains(query, ignoreCase = true) } ||
                    ticket.tags.any { it.contains(query, ignoreCase = true) }

                val matchesCategory = category.isNullOrBlank() ||
                    ticket.category.equals(category, ignoreCase = true)

                val matchesStatus = status == null || ticket.status == status

                matchesQuery && matchesCategory && matchesStatus
            }
            .sortedByDescending { it.updatedAt }
    }

    fun createTicket(
        userId: String,
        subject: String,
        category: String,
        priority: TicketPriority,
        initialMessage: String
    ): Ticket {
        val tickets = getAllTickets().toMutableList()
        val now = Instant.now().toString()

        val newTicket = Ticket(
            id = "ticket_${System.currentTimeMillis()}",
            userId = userId,
            subject = subject,
            status = TicketStatus.OPEN,
            priority = priority,
            category = category,
            createdAt = now,
            updatedAt = now,
            messages = listOf(
                TicketMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    role = "user",
                    content = initialMessage,
                    timestamp = now
                )
            ),
            tags = emptyList()
        )

        tickets.add(newTicket)
        saveTickets(tickets)
        return newTicket
    }

    fun addTicketMessage(ticketId: String, content: String, role: String): Ticket? {
        val tickets = getAllTickets().toMutableList()
        val index = tickets.indexOfFirst { it.id == ticketId }

        if (index == -1) return null

        val ticket = tickets[index]
        val now = Instant.now().toString()

        val newMessage = TicketMessage(
            id = "msg_${System.currentTimeMillis()}",
            role = role,
            content = content,
            timestamp = now
        )

        val updatedTicket = ticket.copy(
            messages = ticket.messages + newMessage,
            updatedAt = now
        )

        tickets[index] = updatedTicket
        saveTickets(tickets)
        return updatedTicket
    }

    fun updateTicketStatus(ticketId: String, status: TicketStatus, resolution: String? = null): Ticket? {
        val tickets = getAllTickets().toMutableList()
        val index = tickets.indexOfFirst { it.id == ticketId }

        if (index == -1) return null

        val ticket = tickets[index]
        val now = Instant.now().toString()

        val updatedTicket = ticket.copy(
            status = status,
            resolution = resolution ?: ticket.resolution,
            updatedAt = now
        )

        tickets[index] = updatedTicket
        saveTickets(tickets)
        return updatedTicket
    }

    private fun saveTickets(tickets: List<Ticket>) {
        ticketsFile.writeText(json.encodeToString(TicketsDatabase(tickets)))
    }

    // ==================== Форматирование для LLM ====================

    fun formatUserForLlm(user: User): String {
        return buildString {
            appendLine("Пользователь: ${user.name}")
            appendLine("Email: ${user.email}")
            user.phone?.let { appendLine("Телефон: $it") }
            user.subscription?.let { sub ->
                appendLine("Подписка: ${sub.plan} (до ${sub.expiresAt})")
                if (sub.features.isNotEmpty()) {
                    appendLine("Функции: ${sub.features.joinToString(", ")}")
                }
            }
            if (user.metadata.isNotEmpty()) {
                appendLine("Дополнительно: ${user.metadata.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
            }
        }
    }

    fun formatTicketForLlm(ticket: Ticket): String {
        return buildString {
            appendLine("Тикет #${ticket.id}")
            appendLine("Тема: ${ticket.subject}")
            appendLine("Статус: ${ticket.status.name.lowercase()}")
            appendLine("Приоритет: ${ticket.priority.name.lowercase()}")
            appendLine("Категория: ${ticket.category}")
            appendLine("Создан: ${ticket.createdAt}")
            appendLine("Обновлён: ${ticket.updatedAt}")
            if (ticket.tags.isNotEmpty()) {
                appendLine("Теги: ${ticket.tags.joinToString(", ")}")
            }
            appendLine()
            appendLine("История сообщений:")
            ticket.messages.forEach { msg ->
                appendLine("  [${msg.role}] ${msg.timestamp}")
                appendLine("  ${msg.content}")
                appendLine()
            }
            ticket.resolution?.let {
                appendLine("Решение: $it")
            }
        }
    }
}
