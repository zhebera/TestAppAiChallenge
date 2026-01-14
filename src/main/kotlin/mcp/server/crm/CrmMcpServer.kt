package org.example.mcp.server.crm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * MCP сервер для работы с CRM системой (пользователи и тикеты)
 *
 * Инструменты:
 * - get_user_by_id: получить пользователя по ID
 * - get_user_by_email: получить пользователя по email
 * - get_user_tickets: получить тикеты пользователя
 * - get_ticket_by_id: получить тикет по ID
 * - create_ticket: создать новый тикет
 * - add_ticket_message: добавить сообщение к тикету
 * - update_ticket_status: обновить статус тикета
 * - search_tickets: поиск тикетов по ключевым словам
 */
class CrmMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val dataManager = CrmDataManager()

    fun run() {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                if (response.isNotEmpty()) {
                    println(json.encodeToString(response))
                    System.out.flush()
                }
            } catch (e: Exception) {
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    }
                }
                println(json.encodeToString(errorResponse))
                System.out.flush()
            }
        }
    }

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> JsonObject(emptyMap())
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "crm-mcp")
                    put("version", "1.0.0")
                }
            }
        }
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    // get_user_by_id
                    addJsonObject {
                        put("name", "get_user_by_id")
                        put("description", "Получить информацию о пользователе по его ID. Возвращает данные пользователя, подписку и метаданные.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("user_id") {
                                    put("type", "string")
                                    put("description", "ID пользователя")
                                }
                            }
                            putJsonArray("required") { add("user_id") }
                        }
                    }

                    // get_user_by_email
                    addJsonObject {
                        put("name", "get_user_by_email")
                        put("description", "Получить информацию о пользователе по email. Возвращает данные пользователя, подписку и метаданные.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("email") {
                                    put("type", "string")
                                    put("description", "Email пользователя")
                                }
                            }
                            putJsonArray("required") { add("email") }
                        }
                    }

                    // get_user_tickets
                    addJsonObject {
                        put("name", "get_user_tickets")
                        put("description", "Получить все тикеты пользователя. Можно фильтровать по статусу.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("user_id") {
                                    put("type", "string")
                                    put("description", "ID пользователя")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "Фильтр по статусу (опционально)")
                                    putJsonArray("enum") {
                                        add("open")
                                        add("in_progress")
                                        add("resolved")
                                        add("closed")
                                    }
                                }
                            }
                            putJsonArray("required") { add("user_id") }
                        }
                    }

                    // get_ticket_by_id
                    addJsonObject {
                        put("name", "get_ticket_by_id")
                        put("description", "Получить тикет по ID с полной историей сообщений.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("ticket_id") {
                                    put("type", "string")
                                    put("description", "ID тикета")
                                }
                            }
                            putJsonArray("required") { add("ticket_id") }
                        }
                    }

                    // create_ticket
                    addJsonObject {
                        put("name", "create_ticket")
                        put("description", "Создать новый тикет поддержки для пользователя.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("user_id") {
                                    put("type", "string")
                                    put("description", "ID пользователя")
                                }
                                putJsonObject("subject") {
                                    put("type", "string")
                                    put("description", "Тема тикета")
                                }
                                putJsonObject("category") {
                                    put("type", "string")
                                    put("description", "Категория: auth, billing, technical, feature_request, other")
                                }
                                putJsonObject("priority") {
                                    put("type", "string")
                                    put("description", "Приоритет тикета")
                                    putJsonArray("enum") {
                                        add("low")
                                        add("medium")
                                        add("high")
                                        add("critical")
                                    }
                                }
                                putJsonObject("initial_message") {
                                    put("type", "string")
                                    put("description", "Первое сообщение от пользователя")
                                }
                            }
                            putJsonArray("required") {
                                add("user_id")
                                add("subject")
                                add("category")
                                add("priority")
                                add("initial_message")
                            }
                        }
                    }

                    // add_ticket_message
                    addJsonObject {
                        put("name", "add_ticket_message")
                        put("description", "Добавить сообщение к существующему тикету.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("ticket_id") {
                                    put("type", "string")
                                    put("description", "ID тикета")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Текст сообщения")
                                }
                                putJsonObject("role") {
                                    put("type", "string")
                                    put("description", "Роль отправителя")
                                    putJsonArray("enum") {
                                        add("user")
                                        add("support")
                                        add("system")
                                    }
                                }
                            }
                            putJsonArray("required") {
                                add("ticket_id")
                                add("content")
                                add("role")
                            }
                        }
                    }

                    // update_ticket_status
                    addJsonObject {
                        put("name", "update_ticket_status")
                        put("description", "Обновить статус тикета. При закрытии можно указать решение.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("ticket_id") {
                                    put("type", "string")
                                    put("description", "ID тикета")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "Новый статус")
                                    putJsonArray("enum") {
                                        add("open")
                                        add("in_progress")
                                        add("resolved")
                                        add("closed")
                                    }
                                }
                                putJsonObject("resolution") {
                                    put("type", "string")
                                    put("description", "Описание решения (опционально, обычно при закрытии)")
                                }
                            }
                            putJsonArray("required") {
                                add("ticket_id")
                                add("status")
                            }
                        }
                    }

                    // search_tickets
                    addJsonObject {
                        put("name", "search_tickets")
                        put("description", "Поиск тикетов по ключевым словам, категории или статусу.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "Поисковый запрос (ищет в теме, сообщениях и тегах)")
                                }
                                putJsonObject("category") {
                                    put("type", "string")
                                    put("description", "Фильтр по категории")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "Фильтр по статусу")
                                    putJsonArray("enum") {
                                        add("open")
                                        add("in_progress")
                                        add("resolved")
                                        add("closed")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val result = when (toolName) {
            "get_user_by_id" -> getUserById(arguments)
            "get_user_by_email" -> getUserByEmail(arguments)
            "get_user_tickets" -> getUserTickets(arguments)
            "get_ticket_by_id" -> getTicketById(arguments)
            "create_ticket" -> createTicket(arguments)
            "add_ticket_message" -> addTicketMessage(arguments)
            "update_ticket_status" -> updateTicketStatus(arguments)
            "search_tickets" -> searchTickets(arguments)
            else -> "Неизвестный инструмент: $toolName"
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
    }

    // ==================== Tool Implementations ====================

    private fun getUserById(arguments: JsonObject): String {
        val userId = arguments["user_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'user_id' обязателен"

        val user = dataManager.getUserById(userId)
            ?: return "Пользователь с ID '$userId' не найден"

        return dataManager.formatUserForLlm(user)
    }

    private fun getUserByEmail(arguments: JsonObject): String {
        val email = arguments["email"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'email' обязателен"

        val user = dataManager.getUserByEmail(email)
            ?: return "Пользователь с email '$email' не найден"

        return dataManager.formatUserForLlm(user)
    }

    private fun getUserTickets(arguments: JsonObject): String {
        val userId = arguments["user_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'user_id' обязателен"

        val statusStr = arguments["status"]?.jsonPrimitive?.content
        val status = statusStr?.let { parseTicketStatus(it) }

        val tickets = dataManager.getTicketsByUserId(userId, status)

        if (tickets.isEmpty()) {
            return if (status != null) {
                "У пользователя '$userId' нет тикетов со статусом '${status.name.lowercase()}'"
            } else {
                "У пользователя '$userId' нет тикетов"
            }
        }

        return buildString {
            appendLine("Найдено тикетов: ${tickets.size}")
            appendLine()
            tickets.forEach { ticket ->
                appendLine(dataManager.formatTicketForLlm(ticket))
                appendLine("---")
            }
        }
    }

    private fun getTicketById(arguments: JsonObject): String {
        val ticketId = arguments["ticket_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'ticket_id' обязателен"

        val ticket = dataManager.getTicketById(ticketId)
            ?: return "Тикет с ID '$ticketId' не найден"

        return dataManager.formatTicketForLlm(ticket)
    }

    private fun createTicket(arguments: JsonObject): String {
        val userId = arguments["user_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'user_id' обязателен"
        val subject = arguments["subject"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'subject' обязателен"
        val category = arguments["category"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'category' обязателен"
        val priorityStr = arguments["priority"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'priority' обязателен"
        val initialMessage = arguments["initial_message"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'initial_message' обязателен"

        val priority = parseTicketPriority(priorityStr)
            ?: return "Ошибка: неверное значение приоритета '$priorityStr'"

        // Проверяем что пользователь существует
        if (dataManager.getUserById(userId) == null) {
            return "Ошибка: пользователь с ID '$userId' не найден"
        }

        val ticket = dataManager.createTicket(userId, subject, category, priority, initialMessage)

        return buildString {
            appendLine("Тикет успешно создан!")
            appendLine()
            appendLine(dataManager.formatTicketForLlm(ticket))
        }
    }

    private fun addTicketMessage(arguments: JsonObject): String {
        val ticketId = arguments["ticket_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'ticket_id' обязателен"
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'content' обязателен"
        val role = arguments["role"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'role' обязателен"

        if (role !in listOf("user", "support", "system")) {
            return "Ошибка: роль должна быть 'user', 'support' или 'system'"
        }

        val ticket = dataManager.addTicketMessage(ticketId, content, role)
            ?: return "Ошибка: тикет с ID '$ticketId' не найден"

        return buildString {
            appendLine("Сообщение добавлено к тикету!")
            appendLine()
            appendLine(dataManager.formatTicketForLlm(ticket))
        }
    }

    private fun updateTicketStatus(arguments: JsonObject): String {
        val ticketId = arguments["ticket_id"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'ticket_id' обязателен"
        val statusStr = arguments["status"]?.jsonPrimitive?.content
            ?: return "Ошибка: параметр 'status' обязателен"
        val resolution = arguments["resolution"]?.jsonPrimitive?.content

        val status = parseTicketStatus(statusStr)
            ?: return "Ошибка: неверное значение статуса '$statusStr'"

        val ticket = dataManager.updateTicketStatus(ticketId, status, resolution)
            ?: return "Ошибка: тикет с ID '$ticketId' не найден"

        return buildString {
            appendLine("Статус тикета обновлён!")
            appendLine()
            appendLine(dataManager.formatTicketForLlm(ticket))
        }
    }

    private fun searchTickets(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.content
        val category = arguments["category"]?.jsonPrimitive?.content
        val statusStr = arguments["status"]?.jsonPrimitive?.content
        val status = statusStr?.let { parseTicketStatus(it) }

        val tickets = dataManager.searchTickets(query, category, status)

        if (tickets.isEmpty()) {
            return "Тикеты не найдены по заданным критериям"
        }

        return buildString {
            appendLine("Найдено тикетов: ${tickets.size}")
            appendLine()
            tickets.take(10).forEach { ticket ->
                appendLine(dataManager.formatTicketForLlm(ticket))
                appendLine("---")
            }
            if (tickets.size > 10) {
                appendLine("... и ещё ${tickets.size - 10} тикетов")
            }
        }
    }

    // ==================== Helpers ====================

    private fun parseTicketStatus(value: String): TicketStatus? {
        return when (value.lowercase()) {
            "open" -> TicketStatus.OPEN
            "in_progress" -> TicketStatus.IN_PROGRESS
            "resolved" -> TicketStatus.RESOLVED
            "closed" -> TicketStatus.CLOSED
            else -> null
        }
    }

    private fun parseTicketPriority(value: String): TicketPriority? {
        return when (value.lowercase()) {
            "low" -> TicketPriority.LOW
            "medium" -> TicketPriority.MEDIUM
            "high" -> TicketPriority.HIGH
            "critical" -> TicketPriority.CRITICAL
            else -> null
        }
    }
}

fun main() {
    CrmMcpServer().run()
}
