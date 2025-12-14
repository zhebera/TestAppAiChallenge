package org.example.app

import kotlinx.coroutines.delay
import org.example.domain.models.ChatHistory
import org.example.domain.models.LlmAnswer

object ResponsePrinter {

    suspend fun printResponse(answer: LlmAnswer) {
        val textToDisplay = if (answer.phase == "ready" && answer.document.isNotBlank()) {
            answer.document
        } else {
            answer.message
        }

        // Плавный вывод текста посимвольно (эффект печатной машинки)
        for (char in textToDisplay) {
            print(char)
            System.out.flush()
            delay(2)
        }

        println()
        println()
    }

    fun printTokenStats(answer: LlmAnswer, chatHistory: ChatHistory) {
        val inputTokens = answer.inputTokens
        val outputTokens = answer.outputTokens
        val stopReason = answer.stopReason
        val stats = chatHistory.getStats()

        if (inputTokens == null && outputTokens == null && stopReason == null) {
            return
        }

        println("─".repeat(60))
        println("Статистика:")

        if (inputTokens != null) {
            println("   Input tokens (запрос):  $inputTokens")
        }
        if (outputTokens != null) {
            println("   Output tokens (ответ):  $outputTokens")
        }
        if (inputTokens != null && outputTokens != null) {
            println("   Всего токенов:          ${inputTokens + outputTokens}")
        }
        if (inputTokens != null && outputTokens != null) {
            val inputCost = inputTokens * 0.003 / 1000  // $3 per MTok
            val outputCost = outputTokens * 0.015 / 1000  // $15 per MTok
            val totalCost = inputCost + outputCost

            println("   Стоимость запроса: $${"%.6f".format(totalCost)}")
        }

        if (stopReason != null) {
            val reasonDescription = when (stopReason) {
                "end_turn" -> "Модель завершила ответ естественно"
                "max_tokens" -> "Ответ обрезан - достигнут лимит max_tokens!"
                "stop_sequence" -> "Остановлено по стоп-последовательности"
                else -> stopReason
            }
            println("   Stop reason:            $reasonDescription")
        }

        // Информация об истории
        println("   История: ${stats.currentMessageCount} сообщений" +
                if (stats.compressedMessageCount > 0) " (+${stats.compressedMessageCount} сжато)" else "")

        println("─".repeat(60))
        println()
    }
}
