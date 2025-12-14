package org.example.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AppConfig {
    const val CLAUDE_SONNET_MODEL = "claude-sonnet-4-20250514"
    const val CLAUDE_HAIKU_MODEL = "claude-haiku-4-5-20251001"
    const val CLAUDE_OPUS_MODEL = "claude-opus-4-1"

    const val DEFAULT_MAX_TOKENS = 1024
    const val MAX_STORED_MESSAGES = 10
    const val COMPRESS_EVERY = 2

    const val REQUEST_TIMEOUT_MS = 150_000L
    const val CONNECT_TIMEOUT_MS = 100_000L
    const val SOCKET_TIMEOUT_MS = 150_000L

    fun buildJson(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
    }

    fun buildHttpClient(json: Json): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
    }
}