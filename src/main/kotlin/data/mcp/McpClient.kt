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
 * Factory for creating MCP clients
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
}