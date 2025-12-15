package org.example.data.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON-RPC 2.0 Request
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject? = null
)

/**
 * JSON-RPC 2.0 Response
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * MCP Initialize Request params
 */
@Serializable
data class InitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject = JsonObject(emptyMap()) // Must be empty object, not null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String
)

/**
 * MCP Initialize Response result
 */
@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: JsonObject? = null,
    val prompts: JsonObject? = null,
    val logging: JsonObject? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String? = null
)

/**
 * MCP Tool definitions
 */
@Serializable
data class ToolsListResult(
    val tools: List<McpTool>
)

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null
)

/**
 * MCP Tool call
 */
@Serializable
data class CallToolParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class CallToolResult(
    val content: List<ToolContent>,
    val isError: Boolean? = null
)

@Serializable
data class ToolContent(
    val type: String,
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null // base64 for blob
)