package org.example.mcp.server.summarizer

import kotlinx.serialization.Serializable

/**
 * Алгоритм извлекающего суммаризатора текста
 * Использует TF-IDF подобный подход для выбора ключевых предложений
 */
class TextSummarizer {

    companion object {
        // Стоп-слова для русского и английского
        private val STOP_WORDS_RU = setOf(
            "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она", "так",
            "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "её", "ее", "мне",
            "было", "вот", "от", "меня", "ещё", "еще", "нет", "о", "из", "ему", "теперь", "когда", "уже",
            "вам", "ни", "быть", "был", "него", "до", "вас", "нибудь", "опять", "уж", "вам", "сказал",
            "ведь", "там", "потом", "себя", "ничего", "ей", "может", "они", "тут", "где", "есть", "надо",
            "ней", "для", "мы", "тебя", "их", "чем", "была", "сам", "чтоб", "без", "будто", "человек",
            "чего", "раз", "тоже", "себе", "под", "жизнь", "будет", "ж", "тогда", "кто", "этот", "говорил",
            "того", "потому", "этого", "какой", "совсем", "ним", "здесь", "этом", "один", "почти", "мой",
            "тем", "чтобы", "нее", "кажется", "сейчас", "были", "куда", "зачем", "сказать", "всех", "можно",
            "при", "это", "эта", "эти", "который", "которая", "которые", "которого"
        )

        private val STOP_WORDS_EN = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
            "from", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do",
            "does", "did", "will", "would", "could", "should", "may", "might", "must", "shall",
            "can", "need", "this", "that", "these", "those", "it", "its", "he", "she", "they",
            "we", "you", "i", "me", "him", "her", "them", "us", "my", "your", "his", "our", "their",
            "what", "which", "who", "whom", "when", "where", "why", "how", "all", "each", "every",
            "both", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only",
            "own", "same", "so", "than", "too", "very", "just", "also", "now", "here", "there"
        )

        private val ALL_STOP_WORDS = STOP_WORDS_RU + STOP_WORDS_EN
    }

    /**
     * Создание саммари текста
     * @param text исходный текст
     * @param maxSentences максимальное количество предложений в саммари
     * @param style стиль саммари: "brief" (краткий), "detailed" (подробный), "bullet" (список)
     */
    fun summarize(text: String, maxSentences: Int = 5, style: String = "brief"): SummaryResult {
        if (text.isBlank()) {
            return SummaryResult(
                success = false,
                summary = "",
                error = "Текст для суммаризации пуст"
            )
        }

        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) {
            return SummaryResult(
                success = false,
                summary = "",
                error = "Не удалось выделить предложения из текста"
            )
        }

        // Если текст короткий, возвращаем как есть
        if (sentences.size <= maxSentences) {
            return SummaryResult(
                success = true,
                summary = formatSummary(sentences, style),
                sentenceCount = sentences.size,
                originalLength = text.length,
                summaryLength = text.length
            )
        }

        // Вычисляем важность каждого предложения
        val wordFrequency = calculateWordFrequency(text)
        val sentenceScores = sentences.mapIndexed { index, sentence ->
            ScoredSentence(
                index = index,
                text = sentence,
                score = calculateSentenceScore(sentence, wordFrequency, index, sentences.size)
            )
        }

        // Выбираем топ предложения, сохраняя порядок появления в тексте
        val topSentences = sentenceScores
            .sortedByDescending { it.score }
            .take(maxSentences)
            .sortedBy { it.index }
            .map { it.text }

        val summary = formatSummary(topSentences, style)

        return SummaryResult(
            success = true,
            summary = summary,
            sentenceCount = topSentences.size,
            originalLength = text.length,
            summaryLength = summary.length,
            compressionRatio = (summary.length.toDouble() / text.length * 100).toInt()
        )
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Разбиваем по точкам, вопросам, восклицаниям
        return text.split(Regex("[.!?]+\\s*"))
            .map { it.trim() }
            .filter { it.length > 10 } // Фильтруем слишком короткие "предложения"
    }

    private fun calculateWordFrequency(text: String): Map<String, Int> {
        return text.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length > 2 && it !in ALL_STOP_WORDS }
            .groupingBy { it }
            .eachCount()
    }

    private fun calculateSentenceScore(
        sentence: String,
        wordFrequency: Map<String, Int>,
        position: Int,
        totalSentences: Int
    ): Double {
        val words = sentence.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length > 2 && it !in ALL_STOP_WORDS }

        if (words.isEmpty()) return 0.0

        // Сумма частотности слов в предложении
        val frequencyScore = words.sumOf { wordFrequency[it] ?: 0 }.toDouble() / words.size

        // Бонус за позицию (первые и последние предложения обычно важнее)
        val positionScore = when {
            position == 0 -> 1.5 // Первое предложение
            position == totalSentences - 1 -> 1.2 // Последнее предложение
            position < 3 -> 1.1 // Начало текста
            else -> 1.0
        }

        // Бонус за длину (средние по длине предложения предпочтительнее)
        val lengthScore = when {
            words.size in 8..25 -> 1.2
            words.size in 5..35 -> 1.0
            else -> 0.8
        }

        return frequencyScore * positionScore * lengthScore
    }

    private fun formatSummary(sentences: List<String>, style: String): String {
        return when (style) {
            "bullet" -> sentences.joinToString("\n") { "• $it" }
            "detailed" -> sentences.joinToString("\n\n")
            else -> sentences.joinToString(" ") // brief
        }
    }

    private data class ScoredSentence(
        val index: Int,
        val text: String,
        val score: Double
    )
}

@Serializable
data class SummaryResult(
    val success: Boolean,
    val summary: String,
    val sentenceCount: Int = 0,
    val originalLength: Int = 0,
    val summaryLength: Int = 0,
    val compressionRatio: Int = 0,
    val error: String? = null
)