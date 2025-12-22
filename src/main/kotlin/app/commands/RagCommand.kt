package org.example.app.commands

import org.example.data.rag.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Команды для работы с RAG (Retrieval-Augmented Generation).
 *
 * Доступные команды:
 * - /rag status   - показать статус индекса
 * - /rag index    - проиндексировать документы из rag_files/
 * - /rag reindex  - переиндексировать всё заново
 * - /rag search <запрос> - поиск по базе знаний
 * - /rag on/off   - включить/выключить автоматический RAG в чате
 */
class RagCommand(
    private val ragService: RagService?
) : Command {

    override fun matches(input: String): Boolean {
        return input.startsWith("/rag")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        if (ragService == null) {
            println("RAG не инициализирован. Проверьте подключение к Ollama.")
            println()
            return CommandResult.Continue
        }

        val parts = input.removePrefix("/rag").trim().split(" ", limit = 2)
        val subCommand = parts.getOrNull(0) ?: ""
        val args = parts.getOrNull(1) ?: ""

        when (subCommand.lowercase()) {
            "", "help" -> printHelp()
            "status" -> showStatus()
            "index" -> indexDocuments(forceReindex = false)
            "reindex" -> indexDocuments(forceReindex = true)
            "search" -> searchDocuments(args)
            "on" -> toggleRag(context, enabled = true)
            "off" -> toggleRag(context, enabled = false)
            else -> {
                println("Неизвестная подкоманда: $subCommand")
                printHelp()
            }
        }

        return CommandResult.Continue
    }

    private fun printHelp() {
        println("""
            |RAG (Retrieval-Augmented Generation) - поиск по базе знаний
            |
            |Команды:
            |  /rag status     - показать статус индекса
            |  /rag index      - проиндексировать документы из rag_files/
            |  /rag reindex    - переиндексировать всё заново
            |  /rag search <q> - поиск по базе знаний
            |  /rag on         - включить автоматический RAG в чате
            |  /rag off        - выключить автоматический RAG
            |
            |Перед использованием:
            |  1. Запустите Ollama: ollama serve
            |  2. Скачайте модель: ollama pull mxbai-embed-large
            |  3. Положите .txt файлы в папку rag_files/
            |  4. Выполните /rag index
        """.trimMargin())
        println()
    }

    private fun showStatus() {
        val stats = ragService!!.getIndexStats()

        println("=== Статус RAG индекса ===")
        println()

        if (stats.totalChunks == 0L) {
            println("Индекс пуст. Выполните /rag index для индексации.")
        } else {
            println("Всего чанков: ${stats.totalChunks}")
            println("Файлов проиндексировано: ${stats.indexedFiles.size}")

            if (stats.indexedFiles.isNotEmpty()) {
                println("\nПроиндексированные файлы:")
                stats.indexedFiles.take(10).forEach { file ->
                    println("  - $file")
                }
                if (stats.indexedFiles.size > 10) {
                    println("  ... и ещё ${stats.indexedFiles.size - 10} файлов")
                }
            }

            stats.lastIndexTime?.let { timestamp ->
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(Date(timestamp))
                println("\nПоследняя индексация: $date")
            }
        }
        println()
    }

    private suspend fun indexDocuments(forceReindex: Boolean) {
        println(if (forceReindex) "Переиндексация документов..." else "Индексация документов...")
        println()

        when (val result = ragService!!.indexDocuments(forceReindex) { status ->
            print("\r[${status.processedFiles}/${status.totalFiles}] ${status.currentFile ?: ""} " +
                    "(чанков: ${status.processedChunks})")
            System.out.flush()
        }) {
            is IndexingResult.Success -> {
                println("\r" + " ".repeat(80) + "\r")  // Очистка строки прогресса
                println("Индексация завершена!")
                println("  Обработано файлов: ${result.filesProcessed}")
                if (result.filesSkipped > 0) {
                    println("  Пропущено (уже в индексе): ${result.filesSkipped}")
                }
                println("  Создано чанков: ${result.chunksCreated}")
            }
            is IndexingResult.NotReady -> {
                when (result.reason) {
                    is ReadinessResult.OllamaNotRunning -> {
                        println("Ollama не запущена!")
                        println("Запустите: ollama serve")
                    }
                    is ReadinessResult.ModelNotFound -> {
                        println("Модель ${result.reason.model} не найдена!")
                        println("Скачайте: ollama pull ${result.reason.model}")
                    }
                    else -> {}
                }
            }
            is IndexingResult.Error -> {
                println("Ошибка: ${result.message}")
            }
        }
        println()
    }

    private suspend fun searchDocuments(query: String) {
        if (query.isBlank()) {
            println("Использование: /rag search <поисковый запрос>")
            println()
            return
        }

        val stats = ragService!!.getIndexStats()
        if (stats.totalChunks == 0L) {
            println("Индекс пуст. Сначала выполните /rag index")
            println()
            return
        }

        println("Поиск: \"$query\"")
        println()

        try {
            val context = ragService.search(query, topK = 5, minSimilarity = 0.3f)

            if (context.results.isEmpty()) {
                println("Ничего не найдено. Попробуйте другой запрос.")
            } else {
                println("Найдено ${context.results.size} результатов:")
                println()

                context.results.forEachIndexed { index, result ->
                    val similarity = "%.1f%%".format(result.similarity * 100)
                    println("--- [${index + 1}] ${result.chunk.sourceFile} (релевантность: $similarity) ---")

                    // Показываем превью контента (первые 300 символов)
                    val preview = result.chunk.content.take(300)
                    println(preview)
                    if (result.chunk.content.length > 300) {
                        println("...")
                    }
                    println()
                }
            }
        } catch (e: Exception) {
            println("Ошибка поиска: ${e.message}")
            println("Убедитесь, что Ollama запущена (ollama serve)")
        }
        println()
    }

    private fun toggleRag(context: CommandContext, enabled: Boolean) {
        context.state.ragEnabled = enabled
        if (enabled) {
            val stats = ragService!!.getIndexStats()
            if (stats.totalChunks == 0L) {
                println("RAG включён, но индекс пуст.")
                println("Выполните /rag index для индексации документов.")
            } else {
                println("RAG включён. Контекст из базы знаний будет добавляться к вопросам.")
                println("(${stats.totalChunks} чанков из ${stats.indexedFiles.size} файлов)")
            }
        } else {
            println("RAG выключен.")
        }
        println()
    }
}