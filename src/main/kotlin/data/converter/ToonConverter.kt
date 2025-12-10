package org.example.data.converter

import dev.toonformat.jtoon.DecodeOptions
import dev.toonformat.jtoon.JToon

/**
 * Utility class for converting TOON-formatted LLM responses to structured data.
 * Uses the official jtoon library (dev.toonformat:jtoon) for parsing.
 */
object ToonConverter {

    private val lenientOptions = DecodeOptions.withStrict(false)

    /**
     * Parses a TOON-formatted string and extracts structured payload fields.
     *
     * @param toonText The raw TOON text from LLM response
     * @return A map containing parsed fields (phase, message, document), or null if parsing fails
     */
    @Suppress("UNCHECKED_CAST")
    fun parseToonResponse(toonText: String): Map<String, Any?>? {
        return try {
            val decoded = JToon.decode(toonText.trim(), lenientOptions)
            decoded as? Map<String, Any?>
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts a StructuredPayload from TOON text.
     *
     * @param toonText The raw TOON text from LLM response
     * @return StructuredPayload with extracted fields, or default values if parsing fails
     */
    fun extractPayload(toonText: String): StructuredPayload {
        val cleanedText = toonText
            .trim()
            .removePrefix("```toon")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // JToon doesn't handle multiline values with colons correctly,
        // so always use the fallback parser which is more robust
        return parseSimpleKeyValue(cleanedText)
    }

    /**
     * Known TOON keys for structured LLM responses.
     */
    private val knownKeys = setOf("phase", "message", "document")

    /**
     * Fallback parser for simple "key: value" TOON format when JToon cannot parse.
     * Only recognizes known keys (phase, message, document) to avoid false positives
     * with colons inside content (e.g., "**Способ 1:**").
     */
    private fun parseSimpleKeyValue(text: String): StructuredPayload {
        val lines = text.lines()
        var phase: String? = null
        var message: String? = null
        var document: String? = null

        var currentKey: String? = null
        val currentValue = StringBuilder()

        for (line in lines) {
            val colonIndex = line.indexOf(':')
            val potentialKey = if (colonIndex > 0) line.substring(0, colonIndex).trim() else null

            // Only treat as a new key if it's one of the known keys and starts at beginning of line
            if (potentialKey != null && potentialKey in knownKeys && !line.startsWith(" ") && !line.startsWith("\t")) {
                // Save previous key-value pair
                if (currentKey != null) {
                    when (currentKey) {
                        "phase" -> phase = currentValue.toString().trim()
                        "message" -> message = currentValue.toString().trim()
                        "document" -> document = currentValue.toString().trim()
                    }
                }
                // Start new key-value pair
                currentKey = potentialKey
                currentValue.clear()
                currentValue.append(line.substring(colonIndex + 1))
            } else if (currentKey != null) {
                // Continue multi-line value
                if (currentValue.isNotEmpty()) {
                    currentValue.append("\n")
                }
                currentValue.append(line)
            }
        }

        // Save last key-value pair
        if (currentKey != null) {
            when (currentKey) {
                "phase" -> phase = currentValue.toString().trim()
                "message" -> message = currentValue.toString().trim()
                "document" -> document = currentValue.toString().trim()
            }
        }

        return StructuredPayload(
            phase = phase,
            document = document,
            message = message ?: text
        )
    }

    /**
     * Converts a structured payload to TOON format for sending to LLM.
     *
     * @param payload The structured data to encode
     * @return TOON-formatted string
     */
    fun encodeToToon(payload: Map<String, Any?>): String {
        return JToon.encode(payload)
    }

    /**
     * Converts a user message to TOON format.
     *
     * @param content The user's message content
     * @return TOON-formatted string representing the user message
     */
    fun encodeUserMessage(content: String): String {
        val payload = mapOf("content" to content)
        return JToon.encode(payload)
    }

    /**
     * Converts a list of conversation messages to TOON format.
     *
     * @param messages List of message maps with role and content
     * @return TOON-formatted string representing the conversation
     */
    fun encodeConversation(messages: List<Map<String, String>>): String {
        val payload = mapOf("messages" to messages)
        return JToon.encode(payload)
    }

    /**
     * Converts TOON text to JSON string.
     *
     * @param toonText The TOON-formatted text
     * @return JSON string representation, or null if conversion fails
     */
    fun toonToJson(toonText: String): String? {
        return try {
            JToon.decodeToJson(toonText.trim(), lenientOptions)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Data class representing a structured LLM response payload.
 */
data class StructuredPayload(
    val phase: String? = null,
    val document: String? = null,
    val message: String,
)