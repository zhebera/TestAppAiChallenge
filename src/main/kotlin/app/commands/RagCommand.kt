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
 * - /rag debug    - включить/выключить показ полного запроса с RAG-контекстом
 * - /rag compare <запрос> - сравнить результаты с реранкингом и без
 * - /rag threshold <0.0-1.0> - настроить порог отсечения
 * - /rag reranker on/off - включить/выключить реранкинг
 * - /rag method <cross/llm/keyword> - выбрать метод реранкинга
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
            "status" -> showStatus(context)
            "index" -> indexDocuments(forceReindex = false)
            "reindex" -> indexDocuments(forceReindex = true)
            "index-project" -> indexProjectFiles(forceReindex = false)
            "reindex-project" -> indexProjectFiles(forceReindex = true)
            "search" -> searchDocuments(args, context)
            "on" -> toggleRag(context, enabled = true)
            "off" -> toggleRag(context, enabled = false)
            "debug" -> toggleDebug(context)
            "compare" -> compareResults(args, context)
            "threshold" -> setThreshold(args, context)
            "reranker" -> toggleReranker(args, context)
            "method" -> setMethod(args, context)
            else -> {
                println("Неизвестная подкоманда: $subCommand")
                printHelp()
            }
        }

        return CommandResult.Continue
    }

    suspend fun initializeProjectIndex() {
        if (ragService == null) return
        
        val stats = ragService.getIndexStats()
        if (stats.totalChunks == 0L) {
            println("Инициализация RAG индекса проекта...")
            indexProjectFiles(forceReindex = false, silent = true)
        }
    }

    private fun printHelp() {
        println("""
            |RAG (Retrieval-Augmented Generation) - поиск по базе знаний
            |
            |Основные команды:
            |  /rag status           - показать статус индекса и настройки реранкинга
            |  /rag index            - проиндексировать документы из rag_files/
            |  /rag reindex          - переиндексировать всё заново
            |  /rag index-project    - проиндексировать файлы проекта (.kt, .md, .kts)
            |  /rag reindex-project  - переиндексировать файлы проекта заново
            |  /rag search <q>       - поиск по базе знаний (с реранкингом если включён)
            |  /rag on               - включить автоматический RAG в чате
            |  /rag off              - выключить автоматический RAG
            |  /rag debug            - вкл/выкл показ полного запроса с RAG-контекстом
            |
            |Реранкинг и фильтрация:
            |  /rag compare <q>      - сравнить результаты с реранкингом и без
            |  /rag threshold <0-1>  - установить порог отсечения (по умолчанию 0.4)
            |  /rag reranker on/off  - включить/выключить реранкинг
            |  /rag method <метод>   - выбрать метод: cross, llm, keyword
            |
            |Методы реранкинга:
            |  cross   - кросс-кодирование через эмбеддинги (быстрый, рекомендуется)
            |  llm     - оценка через LLM (медленный, но точный)
            |  keyword - гибридный: cosine + ключевые слова (самый быстрый)
            |
            |Перед использованием:
            |  1. Запустите Ollama: ollama serve
            |  2. Скачайте модель: ollama pull mxbai-embed-large
            |  3. Положите .txt файлы в папку rag_files/ (или используйте index-project для файлов проекта)
            |  4. Выполните /rag index или /rag index-project
        """.trimMargin())
        println()
    }

    private fun showStatus(context: CommandContext) {
        val stats = ragService!!.getIndexStats()

        println("=== Статус RAG индекса ===")
        println()

        // Показываем текущие настройки RAG
        val ragStatus = if (context.state.ragEnabled) "✓ включён" else "○ выключен"
        val debugStatus = if (context.state.ragDebug) "✓ включён" else "○ выключен"
        println("RAG в чате: $ragStatus")
        println("Режим отладки: $debugStatus")
        println()

        // Показываем настройки реранкинга
        println("=== Настройки реранкинга ===")
        val rerankerStatus = if (context.state.rerankerEnabled) "✓ включён" else "○ выключен"
        val methodName = when (context.state.rerankerMethod) {
            "cross" -> "кросс-кодирование"
            "llm" -> "LLM-оценка"
            "keyword" -> "гибридный (keyword)"
            else -> context.state.rerankerMethod
        }
        println("Реранкинг: $rerankerStatus")
        println("Метод: $methodName")
        println("Порог отсечения: %.0f%%".format(context.state.rerankerThreshold * 100))
        println("Реранкер доступен: ${if (ragService.hasReranker()) "да" else "нет"}")
        println()

        if (stats.totalChunks == 0L) {
            println("Индекс пуст. Выполните /rag index для индексации.")
        } else {
            println("=== Статус индекса ===")
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

    private suspend fun indexProjectFiles(forceReindex: Boolean, silent: Boolean = false) {
        if (!silent) {
            println(if (forceReindex) "Переиндексация файлов проекта..." else "Индексация файлов проекта...")
            println()
        }

        when (val result = ragService!!.indexProjectFiles(forceReindex) { status ->
            if (!silent) {
                print("\r[${status.processedFiles}/${status.totalFiles}] ${status.currentFile ?: ""} " +
                        "(чанков: ${status.processedChunks})")
                System.out.flush()
            }
        }) {
            is IndexingResult.Success -> {
                if (!silent) {
                    println("\r" + " ".repeat(80) + "\r")  // Очистка строки прогресса
                    println("Индексация файлов проекта завершена!")
                    println("  Обработано файлов: ${result.filesProcessed}")
                    if (result.filesSkipped > 0) {
                        println("  Пропущено (уже в индексе): ${result.filesSkipped}")
                    }
                    println("  Создано чанков: ${result.chunksCreated}")
                }
            }
            is IndexingResult.NotReady -> {
                if (!silent) {
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
            }
            is IndexingResult.Error -> {
                if (!silent) {
                    println("Ошибка: ${result.message}")
                }
            }
        }
        if (!silent) {
            println()
        }
    }

    private suspend fun searchDocuments(query: String, context: CommandContext) {
        if (query.isBlank()) {
            println("Использование: /rag search <запрос>")
            println()
            return
        }

        println("Поиск: $query")
        println()

        val results = ragService!!.search(query, limit = 5, useReranker = context.state.rerankerEnabled)

        if (results.isEmpty()) {
            println("Ничего не найдено.")
        } else {
            results.forEachIndexed { index, result ->
                println("${index + 1}. [${result.source}] (релевантность: ${(result.similarity * 100).toInt()}%)")
                println("   ${result.content.take(200)}${if (result.content.length > 200) "..." else ""}")
                println()
            }
        }
    }

    private fun toggleRag(context: CommandContext, enabled: Boolean) {
        context.state.ragEnabled = enabled
        println("RAG ${if (enabled) "включён" else "выключен"}")
        println()
    }

    private fun toggleDebug(context: CommandContext) {
        context.state.ragDebug = !context.state.ragDebug
        println("Режим отладки RAG ${if (context.state.ragDebug) "включён" else "выключен"}")
        println()
    }

    private suspend fun compareResults(query: String, context: CommandContext) {
        if (query.isBlank()) {
            println("Использование: /rag compare <запрос>")
            println()
            return
        }

        println("Сравнение результатов для запроса: $query")
        println()

        // Результаты без реранкинга
        println("=== БЕЗ реранкинга ===")
        val withoutReranker = ragService!!.search(query, limit = 5, useReranker = false)
        if (withoutReranker.isEmpty()) {
            println("Ничего не найдено.")
        } else {
            withoutReranker.forEachIndexed { index, result ->
                println("${index + 1}. [${result.source}] (${(result.similarity * 100).toInt()}%)")
                println("   ${result.content.take(150)}${if (result.content.length > 150) "..." else ""}")
            }
        }

        println()

        // Результаты с реранкингом (если доступен)
        if (ragService.hasReranker()) {
            println("=== С реранкингом (${context.state.rerankerMethod}) ===")
            val withReranker = ragService.search(query, limit = 5, useReranker = true)
            if (withReranker.isEmpty()) {
                println("Ничего не найдено.")
            } else {
                withReranker.forEachIndexed { index, result ->
                    println("${index + 1}. [${result.source}] (${(result.similarity * 100).toInt()}%)")
                    println("   ${result.content.take(150)}${if (result.content.length > 150) "..." else ""}")
                }
            }
        } else {
            println("=== Реранкинг недоступен ===")
            println("Реранкер не инициализирован.")
        }

        println()
    }

    private fun setThreshold(args: String, context: CommandContext) {
        val threshold = args.toDoubleOrNull()