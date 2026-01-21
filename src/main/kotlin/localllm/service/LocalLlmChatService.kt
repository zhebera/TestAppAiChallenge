package org.example.localllm.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.data.api.*
import org.example.localllm.api.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Сервис для чата с локальной LLM через Ollama
 */
class LocalLlmChatService(
    private val http: HttpClient,
    private val json: Json,
    private val ollamaHost: String = System.getenv("OLLAMA_HOST") ?: DEFAULT_HOST,
    private val defaultModel: String = System.getenv("OLLAMA_MODEL") ?: DEFAULT_MODEL
) {
    private val logger = LoggerFactory.getLogger(LocalLlmChatService::class.java)

    companion object {
        const val DEFAULT_HOST = "http://localhost:11434"
        const val DEFAULT_MODEL = "qwen2.5:7b"
        private const val CHAT_ENDPOINT = "/api/chat"
        private const val TAGS_ENDPOINT = "/api/tags"
    }

    // In-memory хранилище сессий (для простоты)
    private val conversations = mutableMapOf<String, MutableList<ChatMessage>>()

    /**
     * Отправить сообщение в чат (без стриминга)
     */
    suspend fun chat(request: ChatRequest): ChatResponse {
        val startTime = System.currentTimeMillis()
        val conversationId = request.conversationId ?: UUID.randomUUID().toString()
        val model = request.model ?: defaultModel

        logger.info("Chat request: model=$model, conversationId=$conversationId")

        // Получаем или создаём историю диалога
        val history = if (request.conversationId != null) {
            conversations.getOrPut(conversationId) { mutableListOf() }
        } else {
            request.history?.toMutableList() ?: mutableListOf()
        }

        // Добавляем текущее сообщение
        history.add(ChatMessage("user", request.message))

        // Формируем запрос к Ollama
        val ollamaRequest = OllamaRequestDto(
            model = model,
            messages = buildOllamaMessages(history, request.systemPrompt),
            stream = false,
            options = OllamaOptionsDto(
                temperature = request.temperature
            )
        )

        try {
            val httpResponse = http.post("$ollamaHost$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(ollamaRequest)
            }

            val responseText = httpResponse.body<String>()
            val ollamaResponse = parseOllamaResponse(responseText)

            val assistantMessage = ollamaResponse.message.content.trim()

            // Сохраняем ответ ассистента в историю
            history.add(ChatMessage("assistant", assistantMessage))
            conversations[conversationId] = history

            val processingTime = System.currentTimeMillis() - startTime
            logger.info("Chat response: success, latency=${processingTime}ms")

            return ChatResponse(
                response = assistantMessage,
                model = ollamaResponse.model,
                conversationId = conversationId,
                inputTokens = ollamaResponse.promptEvalCount,
                outputTokens = ollamaResponse.evalCount,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            logger.error("Chat error", e)
            throw IllegalStateException("Failed to get response from Ollama: ${e.message}", e)
        }
    }

    /**
     * Отправить сообщение в чат со стримингом
     */
    fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
        val conversationId = request.conversationId ?: UUID.randomUUID().toString()
        val model = request.model ?: defaultModel

        logger.info("Stream chat request: model=$model, conversationId=$conversationId")

        // Получаем или создаём историю диалога
        val history = if (request.conversationId != null) {
            conversations.getOrPut(conversationId) { mutableListOf() }
        } else {
            request.history?.toMutableList() ?: mutableListOf()
        }

        // Добавляем текущее сообщение
        history.add(ChatMessage("user", request.message))

        // Формируем запрос к Ollama со стримингом
        val ollamaRequest = OllamaRequestDto(
            model = model,
            messages = buildOllamaMessages(history, request.systemPrompt),
            stream = true,
            options = OllamaOptionsDto(
                temperature = request.temperature
            )
        )

        try {
            val fullResponse = StringBuilder()

            http.preparePost("$ollamaHost$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(ollamaRequest)
            }.execute { response ->
                val channel: ByteReadChannel = response.bodyAsChannel()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue

                    try {
                        val chunk = json.decodeFromString(OllamaResponseDto.serializer(), line)

                        if (chunk.message.content.isNotEmpty()) {
                            fullResponse.append(chunk.message.content)
                            emit(StreamChunk(
                                type = "delta",
                                content = chunk.message.content
                            ))
                        }

                        if (chunk.done) {
                            emit(StreamChunk(
                                type = "done",
                                model = chunk.model,
                                inputTokens = chunk.promptEvalCount,
                                outputTokens = chunk.evalCount
                            ))

                            // Сохраняем полный ответ в историю
                            history.add(ChatMessage("assistant", fullResponse.toString()))
                            conversations[conversationId] = history
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse chunk: $line", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Stream error", e)
            emit(StreamChunk(
                type = "error",
                content = "Error: ${e.message}"
            ))
        }
    }

    /**
     * Проверка здоровья сервиса
     */
    suspend fun checkHealth(): HealthResponse {
        val ollamaAvailable = try {
            val response = http.get("$ollamaHost$TAGS_ENDPOINT")
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.debug("Ollama health check failed: ${e.message}")
            false
        }

        val models = if (ollamaAvailable) {
            try {
                listModels().models.map { it.name }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return HealthResponse(
            status = if (ollamaAvailable) "ok" else "degraded",
            version = "1.0.0",
            ollamaAvailable = ollamaAvailable,
            defaultModel = defaultModel,
            availableModels = models
        )
    }

    /**
     * Получить список доступных моделей
     */
    suspend fun listModels(): ModelsResponse {
        return try {
            val response = http.get("$ollamaHost$TAGS_ENDPOINT")
            val responseText = response.body<String>()
            val tags = json.decodeFromString(OllamaTagsResponseDto.serializer(), responseText)

            ModelsResponse(
                models = tags.models.map { model ->
                    ModelInfo(
                        name = model.name,
                        size = formatSize(model.size),
                        modifiedAt = model.modifiedAt,
                        digest = model.digest
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to list models", e)
            ModelsResponse(emptyList())
        }
    }

    /**
     * Построить список сообщений для Ollama
     */
    private fun buildOllamaMessages(
        history: List<ChatMessage>,
        systemPrompt: String?
    ): List<OllamaMessageDto> {
        val messages = mutableListOf<OllamaMessageDto>()

        // Добавляем системный промпт если есть
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(OllamaMessageDto("system", systemPrompt))
        }

        // Добавляем историю
        messages.addAll(history.map { OllamaMessageDto(it.role, it.content) })

        return messages
    }

    /**
     * Парсинг ответа Ollama (обрабатывает многострочный JSON)
     */
    private fun parseOllamaResponse(responseText: String): OllamaResponseDto {
        val lines = responseText.trim().lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            throw IllegalStateException("Empty response from Ollama")
        }

        val messageBuilder = StringBuilder()
        var finalDto: OllamaResponseDto? = null

        for (line in lines) {
            try {
                val dto = json.decodeFromString(OllamaResponseDto.serializer(), line)

                if (dto.message.content.isNotBlank()) {
                    messageBuilder.append(dto.message.content)
                }

                if (dto.done) {
                    finalDto = dto
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse line: $line", e)
            }
        }

        val result = finalDto ?: throw IllegalStateException("No complete response from Ollama")

        return result.copy(
            message = OllamaMessageDto(
                role = result.message.role,
                content = messageBuilder.toString()
            )
        )
    }

    /**
     * Форматирование размера модели
     */
    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) {
            "%.1f GB".format(gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            "%.1f MB".format(mb)
        }
    }
}
