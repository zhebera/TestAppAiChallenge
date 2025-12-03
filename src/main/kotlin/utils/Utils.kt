package org.example.utils

fun prettyOutput(text: String, maxWidth: Int = 100): String {
    val sb = StringBuilder()
    val normalized = text.replace("\r\n", "\n")

    val paragraphs = normalized.split("\n")

    paragraphs.forEachIndexed { index, paragraph ->
        if (paragraph.isBlank()) {
            // сохраняем пустые строки между абзацами
            sb.appendLine()
        } else {
            var currentLineLength = 0
            val words = paragraph.split(Regex("\\s+"))

            for (word in words) {
                if (word.isEmpty()) continue

                if (word.length >= maxWidth) {
                    if (currentLineLength != 0) {
                        sb.appendLine()
                    }
                    sb.appendLine(word)
                    currentLineLength = 0
                    continue
                }

                if (currentLineLength == 0) {
                    sb.append(word)
                    currentLineLength = word.length
                } else if (currentLineLength + 1 + word.length <= maxWidth) {
                    sb.append(' ').append(word)
                    currentLineLength += 1 + word.length
                } else {
                    sb.appendLine()
                    sb.append(word)
                    currentLineLength = word.length
                }
            }
            sb.appendLine()
        }

        if (index != paragraphs.lastIndex) {
            sb.appendLine()
        }
    }

    return sb.toString().trimEnd()
}