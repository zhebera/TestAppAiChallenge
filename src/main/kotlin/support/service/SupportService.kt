package org.example.support.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.example.data.dto.LlmRequest
import org.example.data.mcp.McpClient
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.example.data.rag.RagService
import org.example.data.rag.ReadinessResult
import org.example.domain.models.ChatRole
import org.example.domain.models.LlmMessage
import org.example.support.api.SupportChatRequest
import org.example.support.api.SupportChatResponse
import org.example.support.api.SupportSource
import org.example.support.api.SuggestedAction
import org.example.support.api.TicketInfo
import org.example.support.api.CreateTicketRequest
import org.example.support.api.UserInfo

/**
 * Сервис поддержки клиентов.
 * Оркестрирует RAG, CRM MCP и LLM для генерации ответов.
 */
class SupportService(
    private val ragService: RagService,
    private val llmClient: LlmClient,
    private val crmMcpClient: McpClient?,
    private val json: Json
) {
    companion object {
        private const val RAG_TOP_K = 3
        private const val RAG_INITIAL_TOP_K = 5
        private const val RAG_MIN_SIMILARITY = 0.4f
        private const val LLM_MAX_TOKENS = 1024
    }

    /**
     * Обработка сообщения от пользователя.
     * 1. Получает контекст из CRM (если есть user_id)
     * 2. Ищет релевантную документацию через RAG
     * 3. Формирует промпт и отправляет в LLM
     * 4. Возвращает ответ с источниками
     */
    suspend fun processMessage(request: SupportChatRequest): SupportChatResponse {
        val sources = mutableListOf<SupportSource>()

        // 1. Получить контекст пользователя из CRM
        val userContext = if (crmMcpClient != null && request.userId != null) {
            try {
                getCrmContext(request.userId, request.ticketId, sources)
            } catch (e: Exception) {
                null // CRM недоступен, продолжаем без контекста
            }
        } else {
            null
        }

        // 2. Поиск релевантной документации через RAG
        val ragContext = try {
            val ragResult = ragService.searchWithReranking(
                query = request.message,
                topK = RAG_TOP_K,
                initialTopK = RAG_INITIAL_TOP_K,
                minSimilarity = RAG_MIN_SIMILARITY
            )

            // Добавляем RAG источники
            ragResult.finalResults.forEach { result ->
                sources.add(
                    SupportSource(
                        type = "rag",
                        file = result.chunk.sourceFile,
                        relevance = result.similarity.toDouble()
                    )
                )
            }

            ragResult.formattedContext
        } catch (e: Exception) {
            // RAG недоступен (Ollama не запущена), продолжаем без него
            ""
        }

        // 3. Сформировать промпт с контекстом
        val systemPrompt = SupportPrompts.buildPrompt(userContext, ragContext)

        // 4. Отправить запрос в LLM
        val llmResponse = llmClient.send(
            LlmRequest(
                model = llmClient.model,
                messages = listOf(LlmMessage(ChatRole.USER, request.message)),
                systemPrompt = systemPrompt,
                maxTokens = LLM_MAX_TOKENS,
                temperature = 0.7
            )
        )

        // 5. Извлечь рекомендуемые действия из ответа
        val suggestedActions = extractSuggestedActions(llmResponse.text)

        return SupportChatResponse(
            response = llmResponse.text,
            sources = sources,
            suggestedActions = suggestedActions,
            sessionId = request.sessionId
        )
    }

    /**
     * Обработка сообщения со streaming (посимвольный вывод).
     * Возвращает Flow с текстом и в конце источники.
     */
    suspend fun processMessageStream(request: SupportChatRequest): Pair<Flow<StreamEvent>, List<SupportSource>> {
        val sources = mutableListOf<SupportSource>()

        // 1. Получить контекст пользователя из CRM
        val userContext = if (crmMcpClient != null && request.userId != null) {
            try {
                getCrmContext(request.userId, request.ticketId, sources)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // 2. Поиск релевантной документации через RAG
        val ragContext = try {
            val ragResult = ragService.searchWithReranking(
                query = request.message,
                topK = RAG_TOP_K,
                initialTopK = RAG_INITIAL_TOP_K,
                minSimilarity = RAG_MIN_SIMILARITY
            )

            ragResult.finalResults.forEach { result ->
                sources.add(
                    SupportSource(
                        type = "rag",
                        file = result.chunk.sourceFile,
                        relevance = result.similarity.toDouble()
                    )
                )
            }

            ragResult.formattedContext
        } catch (e: Exception) {
            ""
        }

        // 3. Сформировать промпт
        val systemPrompt = SupportPrompts.buildPrompt(userContext, ragContext)

        // 4. Вернуть поток от LLM
        val streamFlow = llmClient.sendStream(
            LlmRequest(
                model = llmClient.model,
                messages = listOf(LlmMessage(ChatRole.USER, request.message)),
                systemPrompt = systemPrompt,
                maxTokens = LLM_MAX_TOKENS,
                temperature = 0.7
            )
        )

        return Pair(streamFlow, sources)
    }

    /**
     * Получить контекст пользователя из CRM MCP
     */
    private suspend fun getCrmContext(
        userId: String,
        ticketId: String?,
        sources: MutableList<SupportSource>
    ): String {
        val contextParts = mutableListOf<String>()

        // Получить информацию о пользователе
        try {
            val userResult = crmMcpClient!!.callTool(
                "get_user_by_id",
                mapOf("user_id" to JsonPrimitive(userId))
            )
            val userText = userResult.content.firstOrNull()?.text
            if (!userText.isNullOrBlank() && !userText.contains("не найден")) {
                contextParts.add("### Информация о пользователе:\n$userText")
            }
        } catch (e: Exception) {
            // Игнорируем ошибки получения пользователя
        }

        // Получить конкретный тикет если указан
        if (ticketId != null) {
            try {
                val ticketResult = crmMcpClient!!.callTool(
                    "get_ticket_by_id",
                    mapOf("ticket_id" to JsonPrimitive(ticketId))
                )
                val ticketText = ticketResult.content.firstOrNull()?.text
                if (!ticketText.isNullOrBlank() && !ticketText.contains("не найден")) {
                    contextParts.add("### Текущий тикет:\n$ticketText")
                    sources.add(SupportSource(type = "crm", ticketId = ticketId))
                }
            } catch (e: Exception) {
                // Игнорируем ошибки получения тикета
            }
        }

        // Получить открытые тикеты пользователя
        try {
            val ticketsResult = crmMcpClient!!.callTool(
                "get_user_tickets",
                mapOf(
                    "user_id" to JsonPrimitive(userId),
                    "status" to JsonPrimitive("open")
                )
            )
            val ticketsText = ticketsResult.content.firstOrNull()?.text
            if (!ticketsText.isNullOrBlank() && !ticketsText.contains("нет тикетов")) {
                // Не добавляем если уже есть текущий тикет
                if (ticketId == null) {
                    contextParts.add("### Открытые тикеты пользователя:\n$ticketsText")
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки получения тикетов
        }

        return contextParts.joinToString("\n\n")
    }

    /**
     * Извлечь рекомендуемые действия из ответа LLM
     */
    private fun extractSuggestedActions(text: String): List<SuggestedAction> {
        val actions = mutableListOf<SuggestedAction>()
        val actionPattern = Regex("""\[ACTION:\s*(\w+)\]\s*(.+)""")

        actionPattern.findAll(text).forEach { match ->
            val actionName = match.groupValues[1]
            val description = match.groupValues[2].trim()
            actions.add(SuggestedAction(action = actionName, description = description))
        }

        return actions
    }

    /**
     * Получить тикеты пользователя
     */
    suspend fun getUserTickets(userId: String): List<TicketInfo> {
        if (crmMcpClient == null) return emptyList()

        return try {
            val result = crmMcpClient.callTool(
                "get_user_tickets",
                mapOf("user_id" to JsonPrimitive(userId))
            )
            // Парсим текстовый ответ в структуру (упрощённо)
            parseTicketList(result.content.firstOrNull()?.text ?: "")
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Получить конкретный тикет
     */
    suspend fun getTicket(ticketId: String): TicketInfo? {
        if (crmMcpClient == null) return null

        return try {
            val result = crmMcpClient.callTool(
                "get_ticket_by_id",
                mapOf("ticket_id" to JsonPrimitive(ticketId))
            )
            parseTicketInfo(result.content.firstOrNull()?.text ?: "")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Создать новый тикет
     */
    suspend fun createTicket(request: CreateTicketRequest): TicketInfo? {
        if (crmMcpClient == null) return null

        return try {
            val result = crmMcpClient.callTool(
                "create_ticket",
                mapOf(
                    "user_id" to JsonPrimitive(request.userId),
                    "subject" to JsonPrimitive(request.subject),
                    "category" to JsonPrimitive(request.category),
                    "priority" to JsonPrimitive(request.priority),
                    "initial_message" to JsonPrimitive(request.message)
                )
            )
            parseTicketInfo(result.content.firstOrNull()?.text ?: "")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Получить информацию о пользователе
     */
    suspend fun getUser(userId: String): UserInfo? {
        if (crmMcpClient == null) return null

        return try {
            val result = crmMcpClient.callTool(
                "get_user_by_id",
                mapOf("user_id" to JsonPrimitive(userId))
            )
            parseUserInfo(result.content.firstOrNull()?.text ?: "")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Проверить готовность RAG системы
     */
    suspend fun checkRagStatus(): RagStatus {
        return try {
            val readiness = ragService.checkReadiness()
            val stats = ragService.getIndexStats()

            when (readiness) {
                is ReadinessResult.Ready -> RagStatus(
                    available = true,
                    indexedFiles = stats.indexedFiles.size,
                    totalChunks = stats.totalChunks.toInt()
                )
                is ReadinessResult.OllamaNotRunning -> RagStatus(
                    available = false,
                    error = "Ollama не запущена"
                )
                is ReadinessResult.ModelNotFound -> RagStatus(
                    available = false,
                    error = "Модель ${readiness.model} не найдена"
                )
            }
        } catch (e: Exception) {
            RagStatus(available = false, error = e.message)
        }
    }

    /**
     * Проверить готовность CRM
     */
    fun checkCrmStatus(): CrmStatus {
        return if (crmMcpClient != null && crmMcpClient.isConnected) {
            CrmStatus(available = true)
        } else {
            CrmStatus(available = false, error = "CRM MCP не подключен")
        }
    }

    // ==================== Вспомогательные парсеры ====================

    private fun parseTicketList(text: String): List<TicketInfo> {
        // Упрощённый парсер для текстового ответа от MCP
        val tickets = mutableListOf<TicketInfo>()
        val ticketPattern = Regex("""Тикет #(ticket_\d+)\nТема: (.+)\nСтатус: (\w+)\nПриоритет: (\w+)""")

        ticketPattern.findAll(text).forEach { match ->
            tickets.add(
                TicketInfo(
                    id = match.groupValues[1],
                    subject = match.groupValues[2],
                    status = match.groupValues[3],
                    priority = match.groupValues[4]
                )
            )
        }

        return tickets
    }

    private fun parseTicketInfo(text: String): TicketInfo? {
        val idMatch = Regex("""Тикет #(ticket_\d+)""").find(text) ?: return null
        val subjectMatch = Regex("""Тема: (.+)""").find(text)
        val statusMatch = Regex("""Статус: (\w+)""").find(text)
        val priorityMatch = Regex("""Приоритет: (\w+)""").find(text)

        return TicketInfo(
            id = idMatch.groupValues[1],
            subject = subjectMatch?.groupValues?.get(1) ?: "",
            status = statusMatch?.groupValues?.get(1) ?: "unknown",
            priority = priorityMatch?.groupValues?.get(1) ?: "medium"
        )
    }

    private fun parseUserInfo(text: String): UserInfo? {
        val nameMatch = Regex("""Пользователь: (.+)""").find(text) ?: return null
        val emailMatch = Regex("""Email: (.+)""").find(text)
        val planMatch = Regex("""Подписка: (\w+)""").find(text)

        return UserInfo(
            name = nameMatch.groupValues[1],
            email = emailMatch?.groupValues?.get(1) ?: "",
            plan = planMatch?.groupValues?.get(1)
        )
    }
}

/**
 * Статус RAG системы
 */
data class RagStatus(
    val available: Boolean,
    val indexedFiles: Int = 0,
    val totalChunks: Int = 0,
    val error: String? = null
)

/**
 * Статус CRM системы
 */
data class CrmStatus(
    val available: Boolean,
    val error: String? = null
)
