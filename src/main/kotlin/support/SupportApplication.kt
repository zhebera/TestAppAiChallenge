package org.example.support

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.app.AppConfig
import org.example.data.api.AnthropicClient
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.McpStdioTransport
import org.example.data.persistence.DatabaseConfig
import org.example.data.rag.ChunkingService
import org.example.data.rag.OllamaEmbeddingClient
import org.example.data.rag.RagService
import org.example.data.rag.RerankerService
import org.example.data.rag.VectorStore
import org.example.support.api.ErrorResponse
import org.example.support.api.supportApiModule
import org.example.support.service.SupportService
import java.io.File

/**
 * Entry point для Support API Server
 */
fun main() {
    val port = System.getenv("SUPPORT_API_PORT")?.toIntOrNull() ?: 8080

    println("""
        ╔═══════════════════════════════════════════════════════════════╗
        ║           Support API Server - LLM Chat Application           ║
        ╠═══════════════════════════════════════════════════════════════╣
        ║  Port: $port                                                     ║
        ║  Endpoints:                                                   ║
        ║    POST /api/v1/support/chat     - Chat with support AI       ║
        ║    GET  /api/v1/support/health   - Health check               ║
        ║    GET  /api/v1/support/tickets/{user_id} - User tickets      ║
        ║    GET  /api/v1/support/ticket/{ticket_id} - Ticket details   ║
        ║    POST /api/v1/support/ticket   - Create ticket              ║
        ║    GET  /api/v1/support/user/{user_id} - User info            ║
        ╚═══════════════════════════════════════════════════════════════╝
    """.trimIndent())

    embeddedServer(CIO, port = port) {
        configureSupportApi()
    }.start(wait = true)
}

/**
 * Конфигурация Ktor Application для Support API
 */
fun Application.configureSupportApi() {
    // JSON serialization
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    install(ContentNegotiation) {
        json(json)
    }

    // CORS для веб-клиентов
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
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

    // Initialize services
    println("Initializing services...")

    // Database
    DatabaseConfig.init()

    // HTTP Client
    val httpClient = AppConfig.buildHttpClient(json)

    // Anthropic Client
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    if (anthropicKey.isNullOrBlank()) {
        println("WARNING: ANTHROPIC_API_KEY not set. LLM responses will fail.")
    }

    val anthropicClient = AnthropicClient(
        http = httpClient,
        json = json,
        apiKey = anthropicKey ?: "",
        model = AppConfig.CLAUDE_HAIKU_MODEL  // Haiku для быстрых ответов
    )

    // RAG Service
    val embeddingClient = OllamaEmbeddingClient(httpClient, json)
    val vectorStore = VectorStore()
    val chunkingService = ChunkingService()
    val rerankerService = RerankerService(httpClient, json, embeddingClient)

    val ragService = RagService(
        embeddingClient = embeddingClient,
        vectorStore = vectorStore,
        chunkingService = chunkingService,
        ragDirectory = File("rag_files"),
        rerankerService = rerankerService
    )

    // CRM MCP Client
    val crmMcpClient: McpClient? = try {
        val classpath = System.getProperty("java.class.path")
        val crmConfig = McpClientFactory.createCrmConfig(classpath)
        val transport = McpStdioTransport(crmConfig, json)
        val client = McpClient(transport, json)

        runBlocking {
            println("Connecting to CRM MCP server...")
            val initResult = client.connect()
            println("CRM MCP connected: ${initResult.serverInfo?.name} v${initResult.serverInfo?.version}")
            val tools = client.listTools()
            println("CRM MCP tools available: ${tools.map { it.name }}")
        }

        client
    } catch (e: Exception) {
        println("WARNING: Failed to connect CRM MCP: ${e.message}")
        println("CRM features will be unavailable.")
        null
    }

    // Support Service
    val supportService = SupportService(
        ragService = ragService,
        llmClient = anthropicClient,
        crmMcpClient = crmMcpClient,
        json = json
    )

    // Check RAG status
    runBlocking {
        val ragStatus = supportService.checkRagStatus()
        if (ragStatus.available) {
            println("RAG system ready: ${ragStatus.indexedFiles} files, ${ragStatus.totalChunks} chunks")
        } else {
            println("WARNING: RAG not available: ${ragStatus.error}")
            println("Run 'ollama serve' and index documents with '/rag index' first.")
        }
    }

    // Register routes
    supportApiModule(supportService)

    println("\nSupport API Server started successfully!")
    println("Try: curl http://localhost:${environment?.config?.port ?: 8080}/api/v1/support/health")
}
