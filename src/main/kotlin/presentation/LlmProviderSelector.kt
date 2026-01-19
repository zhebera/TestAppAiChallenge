package org.example.presentation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.example.data.api.AnthropicClient
import org.example.data.api.OllamaClient
import org.example.data.network.LlmClient
import org.slf4j.LoggerFactory

/**
 * Interactive LLM provider selector
 *
 * Provides a user-friendly interface to choose between Claude API and Ollama (local LLM).
 * Performs health checks and guides users through setup if needed.
 */
object LlmProviderSelector {

    private val logger = LoggerFactory.getLogger(LlmProviderSelector::class.java)

    /**
     * Main entry point - select LLM provider interactively
     */
    suspend fun selectProvider(): LlmClient {
        println("\nWelcome to AI Chat Application!")
        println("\nSelect LLM provider:")
        println("1) Claude API (cloud, high quality)")
        println("2) Ollama (local, private)")
        print("\nYour choice [1]: ")

        val choice = readlnOrNull()?.trim() ?: "1"

        return when (choice) {
            "2" -> setupOllama()
            "1", "" -> setupClaude()
            else -> {
                println("Invalid choice. Using Claude API.")
                setupClaude()
            }
        }
    }

    /**
     * Setup Ollama client with health checks and model validation
     */
    private suspend fun setupOllama(): LlmClient {
        val ollamaHttp = createOllamaHttpClient()
        val json = createJson()
        val client = OllamaClient(ollamaHttp, json)

        // Health check loop with retry
        while (true) {
            if (client.checkHealth()) {
                println("✓ Ollama is running")

                // Check if default model is available
                if (!client.checkModelExists(OllamaClient.DEFAULT_MODEL)) {
                    showModelWarning()
                    print("Continue anyway? [y/N]: ")
                    val cont = readlnOrNull()?.trim()?.lowercase()
                    if (cont != "y") {
                        return selectProvider()  // Back to selection
                    }
                }

                println("\nUsing: Ollama (${OllamaClient.DEFAULT_MODEL})\n")
                return client
            } else {
                showOllamaSetupInstructions()
                print("Press Enter to retry, or type 'back' to choose different LLM: ")
                val input = readlnOrNull()?.trim()?.lowercase()
                if (input == "back") {
                    return selectProvider()
                }
            }
        }
    }

    /**
     * Setup Claude client with API key validation
     */
    private fun setupClaude(): LlmClient {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")

        if (apiKey.isNullOrBlank()) {
            println("\n⚠ Warning: ANTHROPIC_API_KEY not set")
            print("Enter API key: ")
            val manualKey = readlnOrNull()?.trim()

            if (manualKey.isNullOrBlank()) {
                throw IllegalStateException(
                    "API key required for Claude.\n" +
                    "Set ANTHROPIC_API_KEY environment variable or provide it when prompted."
                )
            }

            println("\nUsing: Claude API\n")
            return createClaudeClient(manualKey)
        }

        println("\nUsing: Claude API\n")
        return createClaudeClient(apiKey)
    }

    /**
     * Create HTTP client configured for Ollama (120s timeout)
     */
    private fun createOllamaHttpClient(): HttpClient {
        return HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L  // 120 seconds
                connectTimeoutMillis = 10_000L   // 10 seconds
            }
            install(ContentNegotiation) {
                json(createJson())
            }
        }
    }

    /**
     * Create HTTP client configured for Claude API (30s timeout)
     */
    private fun createClaudeHttpClient(): HttpClient {
        return HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L   // 30 seconds
                connectTimeoutMillis = 10_000L   // 10 seconds
            }
            install(ContentNegotiation) {
                json(createJson())
            }
        }
    }

    /**
     * Create Claude client instance
     */
    private fun createClaudeClient(apiKey: String): LlmClient {
        val http = createClaudeHttpClient()
        val json = createJson()
        val model = "claude-sonnet-4-20250514"  // Latest Sonnet model
        return AnthropicClient(http, json, apiKey, model)
    }

    /**
     * Create JSON configuration
     */
    private fun createJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /**
     * Display Ollama setup instructions
     */
    private fun showOllamaSetupInstructions() {
        println("\n✗ Error: Cannot connect to Ollama at ${OllamaClient.DEFAULT_HOST}")
        println("\nOllama is not running. Please follow these steps:")
        println("\n1. Install Ollama (if not installed):")
        println("   macOS:   brew install ollama")
        println("   Linux:   curl -fsSL https://ollama.com/install.sh | sh")
        println("   Windows: Download from https://ollama.com/download")
        println("\n2. Start Ollama service:")
        println("   ollama serve")
        println()
    }

    /**
     * Display model download warning
     */
    private fun showModelWarning() {
        println("\n✗ Warning: Model '${OllamaClient.DEFAULT_MODEL}' is not downloaded")
        println("\nTo download the model, run:")
        println("  ollama pull ${OllamaClient.DEFAULT_MODEL}")
        println("\nThis will download approximately 4.7GB.")
        println("Model will be downloaded automatically on first use, but it may take time.\n")
    }

    /**
     * Get provider name for display
     */
    fun getProviderName(client: LlmClient): String {
        return when (client) {
            is OllamaClient -> "Ollama | ${client.model}"
            is AnthropicClient -> "Claude API | ${client.model}"
            else -> "Unknown | ${client.model}"
        }
    }
}
