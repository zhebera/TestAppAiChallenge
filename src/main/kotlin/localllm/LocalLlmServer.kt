package org.example.localllm

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.example.app.AppConfig
import org.example.localllm.api.ErrorResponse
import org.example.localllm.api.localLlmApiModule
import org.example.localllm.service.LocalLlmChatService

/**
 * Точка входа для Local LLM Chat Server
 *
 * Сервер для чата с локальной LLM (Ollama) через REST API.
 * Поддерживает:
 * - Обычные запросы (POST /api/v1/chat)
 * - Стриминг ответов (POST /api/v1/chat/stream)
 * - Проверку здоровья (GET /api/v1/health)
 * - Список моделей (GET /api/v1/models)
 *
 * Переменные окружения:
 * - LOCAL_LLM_PORT: порт сервера (по умолчанию 8081)
 * - OLLAMA_HOST: адрес Ollama (по умолчанию http://localhost:11434)
 * - OLLAMA_MODEL: модель по умолчанию (по умолчанию qwen2.5:7b)
 */
fun main() {
    val port = System.getenv("LOCAL_LLM_PORT")?.toIntOrNull() ?: 8081
    val ollamaHost = System.getenv("OLLAMA_HOST") ?: "http://localhost:11434"
    val defaultModel = System.getenv("OLLAMA_MODEL") ?: "qwen2.5:7b"

    println("""
        ╔═══════════════════════════════════════════════════════════════╗
        ║         Local LLM Chat Server - REST API for Ollama           ║
        ╠═══════════════════════════════════════════════════════════════╣
        ║  Port: $port                                                     ║
        ║  Ollama: $ollamaHost
        ║  Model: $defaultModel
        ╠═══════════════════════════════════════════════════════════════╣
        ║  Endpoints:                                                   ║
        ║    POST /api/v1/chat         - Chat (sync)                    ║
        ║    POST /api/v1/chat/stream  - Chat (streaming SSE)           ║
        ║    GET  /api/v1/health       - Health check                   ║
        ║    GET  /api/v1/models       - List available models          ║
        ║    GET  /api/v1/ping         - Simple ping                    ║
        ╚═══════════════════════════════════════════════════════════════╝
    """.trimIndent())

    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureLocalLlmServer(ollamaHost, defaultModel)
    }.start(wait = true)
}

/**
 * Конфигурация Ktor Application для Local LLM Server
 */
fun Application.configureLocalLlmServer(
    ollamaHost: String = System.getenv("OLLAMA_HOST") ?: "http://localhost:11434",
    defaultModel: String = System.getenv("OLLAMA_MODEL") ?: "qwen2.5:7b"
) {
    // JSON serialization
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    install(ContentNegotiation) {
        json(json)
    }

    // CORS для доступа с любых хостов
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }

    // Error handling
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Unknown error", 500)
            )
        }
    }

    // HTTP Client для Ollama
    val httpClient = AppConfig.buildHttpClient(json)

    // Chat Service
    val chatService = LocalLlmChatService(
        http = httpClient,
        json = json,
        ollamaHost = ollamaHost,
        defaultModel = defaultModel
    )

    // Проверяем доступность Ollama при старте
    println("\nChecking Ollama availability...")
    kotlinx.coroutines.runBlocking {
        val health = chatService.checkHealth()
        if (health.ollamaAvailable) {
            println("✓ Ollama is available at $ollamaHost")
            println("✓ Available models: ${health.availableModels.joinToString(", ")}")
        } else {
            println("✗ WARNING: Ollama is not available at $ollamaHost")
            println("  Make sure Ollama is running: ollama serve")
        }
    }

    // Register routes
    localLlmApiModule(chatService, json)

    println("\n✓ Local LLM Chat Server started successfully!")
    println("  Test with: curl http://localhost:${environment?.config?.port ?: 8081}/api/v1/health")
}
