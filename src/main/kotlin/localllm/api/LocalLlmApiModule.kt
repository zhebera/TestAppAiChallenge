package org.example.localllm.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.localllm.service.LocalLlmChatService

/**
 * Модуль Ktor для REST API локальной LLM
 */
fun Application.localLlmApiModule(service: LocalLlmChatService, json: Json) {
    routing {
        route("/api/v1") {

            // Health check
            get("/health") {
                val health = service.checkHealth()
                call.respond(health)
            }

            // Список доступных моделей
            get("/models") {
                try {
                    val models = service.listModels()
                    call.respond(models)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to list models: ${e.message}", 500)
                    )
                }
            }

            // Основной endpoint чата (без стриминга)
            post("/chat") {
                try {
                    val request = call.receive<ChatRequest>()

                    if (request.message.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Message cannot be empty", 400)
                        )
                        return@post
                    }

                    if (request.stream) {
                        // Стриминг через SSE
                        call.response.cacheControl(CacheControl.NoCache(null))
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            service.chatStream(request)
                                .onEach { chunk ->
                                    write("data: ${json.encodeToString(chunk)}\n\n")
                                    flush()
                                }
                                .collect()
                        }
                    } else {
                        // Обычный запрос
                        val response = service.chat(request)
                        call.respond(response)
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error processing message: ${e.message}", 500)
                    )
                }
            }

            // Endpoint для стриминга (Server-Sent Events)
            post("/chat/stream") {
                try {
                    val request = call.receive<ChatRequest>()

                    if (request.message.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Message cannot be empty", 400)
                        )
                        return@post
                    }

                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        service.chatStream(request)
                            .onEach { chunk ->
                                write("data: ${json.encodeToString(chunk)}\n\n")
                                flush()
                            }
                            .collect()
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Error processing stream: ${e.message}", 500)
                    )
                }
            }

            // Простой ping для проверки связи
            get("/ping") {
                call.respondText("pong", ContentType.Text.Plain)
            }

            // Информация о сервере
            get("/info") {
                call.respond(mapOf(
                    "name" to "Local LLM Chat Server",
                    "version" to "1.0.0",
                    "description" to "REST API для чата с локальной LLM (Ollama)"
                ))
            }
        }
    }
}
