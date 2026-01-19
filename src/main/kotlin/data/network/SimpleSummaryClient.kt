package org.example.data.network

/**
 * Simple stub SummaryClient for Ollama
 * Returns a basic summary without LLM calls for MVP
 */
class SimpleSummaryClient : SummaryClient {
    override suspend fun summarize(dialogText: String): String {
        // For MVP: simple stub that returns truncated text
        val lines = dialogText.lines()
        return if (lines.size > 10) {
            "[Compressed ${lines.size} messages]\n" +
            lines.take(3).joinToString("\n") +
            "\n...\n" +
            lines.takeLast(3).joinToString("\n")
        } else {
            dialogText
        }
    }
}
