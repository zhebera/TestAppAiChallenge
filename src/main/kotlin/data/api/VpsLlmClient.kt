package org.example.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.slf4j.LoggerFactory

/**
 * VPS LLM Client - клиент для работы с удалённой LLM на VPS
 *
 * Подключается к Local LLM Chat Server, развёрнутому на VPS.
 * Использует REST API для отправки сообщений и получения ответов.
 */
class VpsLlmClient(
    private val http: HttpClient,
    private val json: Json,
    private val serverUrl: String = System.getenv("VPS_LLM_URL") ?: DEFAULT_SERVER_URL,
    override val model: String = DEFAULT_MODEL
) : LlmClient {

    private val logger = LoggerFactory.getLogger(VpsLlmClient::class.java)

    // Хранилище conversation_id для поддержки контекста диалога
    private var currentConversationId: String? = null

    companion object {
        const val DEFAULT_SERVER_URL = "http://89.104.74.205:8081"
        const val DEFAULT_MODEL = "qwen2.5:0.5b"
        private const val CHAT_ENDPOINT = "/api/v1/chat"
        private const val HEALTH_ENDPOINT = "/api/v1/health"
        private const val MODELS_ENDPOINT = "/api/v1/models"
    }

    /**
     * Отправить запрос к VPS LLM серверу
     */
    override suspend fun send(request: LlmRequest): LlmResponse {
        logger.info("VPS LLM request: model=$model, messages=${request.messages.size}")

        val startTime = System.currentTimeMillis()

        // Берём последнее сообщение пользователя
        val userMessage = request.messages.lastOrNull { it.role.name.equals("USER", ignoreCase = true) }
            ?: request.messages.lastOrNull()

        if (userMessage == null) {
            throw IllegalStateException("No user message found in request")
        }

        // Формируем историю для контекста (без последнего сообщения)
        val history = request.messages.dropLast(1).map { msg ->
            VpsChatMessage(
                role = msg.role.name.toLowerCasePreservingASCIIRules(),
                content = msg.content
            )
        }

        val chatRequest = VpsChatRequest(
            message = userMessage.content,
            conversationId = currentConversationId,
            history = if (history.isNotEmpty()) history else null,
            systemPrompt = request.systemPrompt,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            model = model,
            stream = false
        )

        try {
            val httpResponse = http.post("$serverUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(chatRequest)
            }

            val responseText = httpResponse.body<String>()
            val chatResponse = json.decodeFromString(VpsChatResponse.serializer(), responseText)

            // Сохраняем conversation_id для следующих запросов
            currentConversationId = chatResponse.conversationId

            val latency = System.currentTimeMillis() - startTime
            logger.info("VPS LLM response: success, latency=${latency}ms, tokens=${chatResponse.outputTokens}")

            return LlmResponse(
                model = chatResponse.model,
                text = chatResponse.response,
                rawJson = chatResponse.response,
                inputTokens = chatResponse.inputTokens,
                outputTokens = chatResponse.outputTokens,
                stopReason = "stop"
            )
        } catch (e: Exception) {
            logger.error("VPS LLM request failed", e)
            throw IllegalStateException("VPS LLM error: ${e.message}", e)
        }
    }

    /**
     * Стриминг (пока делегирует к обычному send)
     */
    override fun sendStream(request: LlmRequest): Flow<StreamEvent> = flow {
        logger.info("VPS LLM streaming request (fallback to non-streaming)")

        val response = send(request)

        emit(StreamEvent.Complete(
            model = response.model,
            fullText = response.text,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            stopReason = response.stopReason
        ))
    }

    /**
     * Проверка доступности VPS сервера
     */
    suspend fun checkHealth(): Boolean {
        return try {
            val response = http.get("$serverUrl$HEALTH_ENDPOINT")
            if (response.status.value in 200..299) {
                val body = response.body<String>()
                val health = json.decodeFromString(VpsHealthResponse.serializer(), body)
                health.status == "ok" && health.ollamaAvailable
            } else {
                false
            }
        } catch (e: Exception) {
            logger.debug("VPS health check failed: ${e.message}")
            false
        }
    }

    /**
     * Получить информацию о сервере
     */
    suspend fun getServerInfo(): VpsHealthResponse? {
        return try {
            val response = http.get("$serverUrl$HEALTH_ENDPOINT")
            val body = response.body<String>()
            json.decodeFromString(VpsHealthResponse.serializer(), body)
        } catch (e: Exception) {
            logger.error("Failed to get server info", e)
            null
        }
    }

    /**
     * Получить список моделей на VPS
     */
    suspend fun listModels(): List<String> {
        return try {
            val response = http.get("$serverUrl$MODELS_ENDPOINT")
            val body = response.body<String>()
            val models = json.decodeFromString(VpsModelsResponse.serializer(), body)
            models.models.map { it.name }
        } catch (e: Exception) {
            logger.error("Failed to list models", e)
            emptyList()
        }
    }

    /**
     * Сбросить контекст диалога
     */
    fun resetConversation() {
        currentConversationId = null
        logger.info("Conversation context reset")
    }
}

// DTO для VPS API

@Serializable
data class VpsChatRequest(
    val message: String,
    @SerialName("conversation_id")
    val conversationId: String? = null,
    val history: List<VpsChatMessage>? = null,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val model: String? = null,
    val stream: Boolean = false
)

@Serializable
data class VpsChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class VpsChatResponse(
    val response: String,
    val model: String,
    @SerialName("conversation_id")
    val conversationId: String? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("processing_time_ms")
    val processingTimeMs: Long? = null
)

@Serializable
data class VpsHealthResponse(
    val status: String,
    val version: String,
    @SerialName("ollama_available")
    val ollamaAvailable: Boolean,
    @SerialName("default_model")
    val defaultModel: String,
    @SerialName("available_models")
    val availableModels: List<String> = emptyList()
)

@Serializable
data class VpsModelsResponse(
    val models: List<VpsModelInfo>
)

@Serializable
data class VpsModelInfo(
    val name: String,
    val size: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    val digest: String? = null
)
