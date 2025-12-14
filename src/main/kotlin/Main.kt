package org.example

import kotlinx.coroutines.runBlocking
import org.example.app.AppConfig
import org.example.app.AppInitializer
import org.example.app.ChatLoop
import org.example.data.persistence.DatabaseConfig
import org.example.data.persistence.MemoryRepository
import org.example.presentation.ConsoleInput

fun main() = runBlocking {
    DatabaseConfig.init()
    val memoryRepository = MemoryRepository()

    ConsoleInput().use { console ->
        val anthropicKey = AppInitializer.resolveApiKey(
            console, "ANTHROPIC_API_KEY", "Anthropic"
        ) ?: return@runBlocking

        val openRouterKey = AppInitializer.resolveApiKey(
            console, "OPENROUTER_API_KEY", "OpenRouter (для сжатия истории)"
        )

        val json = AppConfig.buildJson()
        val client = AppConfig.buildHttpClient(json)

        try {
            val useCases = AppInitializer.buildUseCases(client, json, anthropicKey, openRouterKey)
            ChatLoop(console, useCases, memoryRepository).run()
        } finally {
            client.close()
        }
    }
}
