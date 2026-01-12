package org.example.data.mcp

import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP Client for communicating with MCP servers
 */
class McpClient(
    private val transport: McpStdioTransport,
    private val json: Json
) {
    private val requestId = AtomicInteger(1)
    private var serverCapabilities: ServerCapabilities? = null
    private var serverInfo: ServerInfo? = null

    val isConnected: Boolean
        get() = transport.isRunning && serverCapabilities != null

    /**
     * Connect to the MCP server and perform initialization handshake
     */
    suspend fun connect(): InitializeResult {
        // Start the transport (launches the server process)
        transport.start()

        // Send initialize request
        val initParams = InitializeParams(
            clientInfo = ClientInfo(
                name = "kotlin-mcp-client",
                version = "1.0.0"
            ),
            capabilities = ClientCapabilities(
                roots = RootsCapability(listChanged = false)
            )
        )

        val paramsJson = json.encodeToJsonElement(InitializeParams.serializer(), initParams).jsonObject

        val initRequest = JsonRpcRequest(
            id = requestId.getAndIncrement(),
            method = "initialize",
            params = paramsJson
        )

        transport.send(initRequest)
        val response = transport.receive(initRequest.id)

        if (response.error != null) {
            throw McpException("Initialize failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(InitializeResult.serializer(), response.result!!)
        serverCapabilities = result.capabilities
        serverInfo = result.serverInfo

        // Send initialized notification
        transport.sendNotification("notifications/initialized")

        return result
    }

    /**
     * Get the list of available tools from the MCP server
     */
    suspend fun listTools(): List<McpTool> {
        ensureConnected()

        val request = JsonRpcRequest(
            id = requestId.getAndIncrement(),
            method = "tools/list",
            params = null
        )

        transport.send(request)
        val response = transport.receive(request.id)

        if (response.error != null) {
            throw McpException("tools/list failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(ToolsListResult.serializer(), response.result!!)
        return result.tools
    }

    /**
     * Call a tool on the MCP server
     */
    suspend fun callTool(name: String, arguments: Map<String, JsonElement> = emptyMap()): CallToolResult {
        ensureConnected()

        val callParams = CallToolParams(
            name = name,
            arguments = if (arguments.isEmpty()) null else JsonObject(arguments)
        )

        val paramsJson = json.encodeToJsonElement(CallToolParams.serializer(), callParams).jsonObject

        val request = JsonRpcRequest(
            id = requestId.getAndIncrement(),
            method = "tools/call",
            params = paramsJson
        )

        transport.send(request)
        val response = transport.receive(request.id, timeoutMs = 60000) // Longer timeout for tool execution

        if (response.error != null) {
            throw McpException("tools/call failed: ${response.error.message}")
        }

        return json.decodeFromJsonElement(CallToolResult.serializer(), response.result!!)
    }

    /**
     * Disconnect from the MCP server
     */
    fun disconnect() {
        transport.stop()
        serverCapabilities = null
        serverInfo = null
    }

    /**
     * Get server information
     */
    fun getServerInfo(): ServerInfo? = serverInfo

    /**
     * Get server capabilities
     */
    fun getServerCapabilities(): ServerCapabilities? = serverCapabilities

    private fun ensureConnected() {
        if (!isConnected) {
            throw McpException("Not connected to MCP server. Call connect() first.")
        }
    }
}

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Factory for creating MCP clients and configurations
 */
object McpClientFactory {

    fun createJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Create an MCP client for the GitHub server
     * Requires GITHUB_PERSONAL_ACCESS_TOKEN environment variable or token parameter
     */
    fun createGitHubClient(
        token: String? = null,
        json: Json = createJson()
    ): McpClient {
        val githubToken = token ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
            ?: throw McpException("GitHub token not provided. Set GITHUB_PERSONAL_ACCESS_TOKEN env var or pass token parameter.")

        val config = McpServerConfig(
            command = "npx",
            args = listOf(
                "-y",
                "@modelcontextprotocol/server-github"
            ),
            env = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to githubToken)
        )
        val transport = McpStdioTransport(config, json)
        return McpClient(transport, json)
    }

    /**
     * Конфигурация для Wikipedia MCP сервера
     */
    fun createWikipediaConfig(classpath: String): McpServerConfig {
        return McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.wikipedia.WikipediaMcpServerKt"
            )
        )
    }

    /**
     * Конфигурация для Summarizer MCP сервера
     */
    fun createSummarizerConfig(classpath: String): McpServerConfig {
        return McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.summarizer.SummarizerMcpServerKt"
            )
        )
    }

    /**
     * Конфигурация для FileStorage MCP сервера
     */
    fun createFileStorageConfig(classpath: String): McpServerConfig {
        return McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.filestorage.FileStorageMcpServerKt"
            )
        )
    }

    /**
     * Конфигурация для Android Emulator MCP сервера
     */
    fun createAndroidEmulatorConfig(classpath: String): McpServerConfig {
        return McpServerConfig(
            command = "java",
            args = listOf(
                "-cp", classpath,
                "org.example.mcp.server.android.AndroidEmulatorMcpServerKt"
            ),
            env = mapOf(
                "ANDROID_HOME" to (System.getenv("ANDROID_HOME") ?: "/Users/andrei/Library/Android/sdk")
            )
        )
    }

    /**
     * Конфигурация для Git MCP сервера (официальный Python сервер через uvx).
     * Предоставляет инструменты для работы с git репозиторием.
     */
    fun createGitServerConfig(): McpServerConfig {
        return McpServerConfig(
            command = "uvx",
            args = listOf(
                "mcp-server-git",
                "--repository", System.getProperty("user.dir")
            ),
            env = mapOf()
        )
    }

    /**
     * Получить все локальные MCP конфигурации (без Wikipedia по умолчанию -
     * используйте RAG вместо него или включите вручную через /mcp connect wikipedia)
     */
    fun getAllLocalServerConfigs(classpath: String): Map<String, McpServerConfig> {
        return mapOf(
            "git" to createGitServerConfig(),
            // "wikipedia" - отключён по умолчанию (дублирует RAG)
            "summarizer" to createSummarizerConfig(classpath),
            "filestorage" to createFileStorageConfig(classpath),
            "android" to createAndroidEmulatorConfig(classpath)
        )
    }

    /**
     * Получить все доступные MCP конфигурации (включая отключённые)
     */
    fun getAllAvailableServerConfigs(classpath: String): Map<String, McpServerConfig> {
        return mapOf(
            "wikipedia" to createWikipediaConfig(classpath),
            "summarizer" to createSummarizerConfig(classpath),
            "filestorage" to createFileStorageConfig(classpath),
            "android" to createAndroidEmulatorConfig(classpath)
        )
    }
}