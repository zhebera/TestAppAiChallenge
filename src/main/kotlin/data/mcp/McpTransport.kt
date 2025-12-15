package org.example.data.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Configuration for launching an MCP server process
 */
data class McpServerConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null
)

/**
 * Transport layer for MCP communication via stdio (stdin/stdout)
 */
class McpStdioTransport(
    private val config: McpServerConfig,
    private val json: Json
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null

    private val responseChannel = Channel<JsonRpcResponse>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Start the MCP server process
     */
    fun start() {
        val processBuilder = ProcessBuilder(buildCommand())

        // Merge environment variables
        val environment = processBuilder.environment()
        config.env.forEach { (key, value) -> environment[key] = value }

        config.workingDir?.let { processBuilder.directory(java.io.File(it)) }

        processBuilder.redirectErrorStream(false)

        process = processBuilder.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

        // Start reading responses in background thread
        readerJob = scope.launch(Dispatchers.IO) {
            readResponses()
        }

        // Consume stderr silently to prevent blocking
        scope.launch(Dispatchers.IO) {
            process?.errorStream?.bufferedReader()?.readText()
        }
    }

    /**
     * Send a JSON-RPC request to the server
     */
    suspend fun send(request: JsonRpcRequest) {
        // Build JSON manually to handle null params correctly (omit or use {})
        val jsonStr = buildString {
            append("""{"jsonrpc":"2.0","id":${request.id},"method":"${request.method}"""")
            if (request.params != null) {
                append(""","params":${request.params}""")
            }
            append("}")
        }
        withContext(Dispatchers.IO) {
            writer?.apply {
                write(jsonStr)
                newLine()
                flush()
            }
        }
    }

    /**
     * Send a notification (no response expected)
     */
    suspend fun sendNotification(method: String) {
        val notification = JsonRpcRequest(
            id = 0, // notifications don't need meaningful id
            method = method,
            params = null
        )
        val jsonStr = json.encodeToString(JsonRpcRequest.serializer(), notification)
            .replace("\"id\":0,", "") // Remove id for notification
        withContext(Dispatchers.IO) {
            writer?.apply {
                write(jsonStr)
                newLine()
                flush()
            }
        }
    }

    /**
     * Wait for a response with the given id
     */
    suspend fun receive(expectedId: Int, timeoutMs: Long = 30000): JsonRpcResponse {
        return withTimeout(timeoutMs) {
            while (true) {
                val response = responseChannel.receive()
                if (response.id == expectedId) {
                    return@withTimeout response
                }
                // Put back responses with different ids (shouldn't happen often with sequential calls)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Unreachable")
        }
    }

    /**
     * Stop the MCP server process
     */
    fun stop() {
        readerJob?.cancel()
        scope.cancel()

        runCatching {
            writer?.close()
            reader?.close()
        }

        process?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }

        process = null
        writer = null
        reader = null
    }

    private fun buildCommand(): List<String> {
        return listOf(config.command) + config.args
    }

    private fun readResponses() {
        val r = reader ?: return
        try {
            while (true) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) {
                    try {
                        val response = json.decodeFromString(JsonRpcResponse.serializer(), line)
                        responseChannel.trySend(response)
                    } catch (_: Exception) {
                        // Ignore malformed responses
                    }
                }
            }
        } catch (_: Exception) {
            // Reader closed or error
        }
    }
}