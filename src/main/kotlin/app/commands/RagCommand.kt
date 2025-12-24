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

    private fun printHelp() {
        println("""
            |RAG (Retrieval-Augmented Generation) - поиск по базе знаний
            |
            |Основные команды:
            |  /rag status     - показать статус индекса и настройки реранкинга
            |  /rag index      - проиндексировать документы из rag_files/
            |  /rag reindex    - переиндексировать всё заново
            |  /rag search <q> - поиск по базе знаний (с реранкингом если включён)
            |  /rag on         - включить автоматический RAG в чате
            |  /rag off        - выключить автоматический RAG
            |  /rag debug      - вкл/выкл показ полного запроса с RAG-контекстом
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
            |  3. Положите .txt файлы в папку rag_files/
            |  4. Выполните /rag index
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

    private suspend fun searchDocuments(query: String, context: CommandContext) {
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

        val useReranking = context.state.rerankerEnabled && ragService.hasReranker()
        println("Поиск: \"$query\"")
        println("Режим: ${if (useReranking) "с реранкингом" else "без реранкинга"}")
        println()

        try {
            if (useReranking) {
                // Поиск с реранкингом
                val config = createRerankerConfig(context)
                val result = ragService.searchWithReranking(
                    query = query,
                    topK = 5,
                    initialTopK = 10,
                    minSimilarity = 0.25f,
                    rerankerConfig = config
                )

                if (result.finalResults.isEmpty()) {
                    println("Ничего не найдено после фильтрации (порог: %.0f%%)".format(config.threshold * 100))
                    println("Попробуйте понизить порог командой: /rag threshold 0.3")
                } else {
                    println("Найдено ${result.finalResults.size} результатов")
                    if (result.filteredCount > 0) {
                        println("(отфильтровано ${result.filteredCount} нерелевантных)")
                    }
                    println()

                    // Показываем реранкнутые результаты
                    result.rerankedResults
                        .filter { !it.wasFiltered }
                        .take(5)
                        .forEachIndexed { index, reranked ->
                            val newScore = "%.1f%%".format(reranked.rerankedScore * 100)
                            val oldScore = "%.1f%%".format(reranked.originalScore * 100)
                            val delta = reranked.rerankedScore - reranked.originalScore
                            val deltaStr = if (delta >= 0) "+%.1f%%".format(delta * 100) else "%.1f%%".format(delta * 100)

                            println("--- [${index + 1}] ${reranked.original.chunk.sourceFile} ---")
                            println("    Скор: $newScore (было: $oldScore, изменение: $deltaStr)")

                            val preview = reranked.original.chunk.content.take(250)
                            println(preview)
                            if (reranked.original.chunk.content.length > 250) {
                                println("...")
                            }
                            println()
                        }
                }
            } else {
                // Обычный поиск без реранкинга
                val ragContext = ragService.search(query, topK = 5, minSimilarity = 0.3f)

                if (ragContext.results.isEmpty()) {
                    println("Ничего не найдено. Попробуйте другой запрос.")
                } else {
                    println("Найдено ${ragContext.results.size} результатов:")
                    println()

                    ragContext.results.forEachIndexed { index, result ->
                        val similarity = "%.1f%%".format(result.similarity * 100)
                        println("--- [${index + 1}] ${result.chunk.sourceFile} (релевантность: $similarity) ---")

                        val preview = result.chunk.content.take(300)
                        println(preview)
                        if (result.chunk.content.length > 300) {
                            println("...")
                        }
                        println()
                    }
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

    private fun toggleDebug(context: CommandContext) {
        context.state.ragDebug = !context.state.ragDebug
        if (context.state.ragDebug) {
            println("Режим отладки RAG включён.")
            println("При отправке сообщений будет показан полный запрос с RAG-контекстом.")
        } else {
            println("Режим отладки RAG выключен.")
        }
        println()
    }

    /**
     * Сравнение результатов с реранкингом и без.
     * Демонстрирует эффект фильтрации и переранжирования.
     */
    private suspend fun compareResults(query: String, context: CommandContext) {
        if (query.isBlank()) {
            println("Использование: /rag compare <поисковый запрос>")
            println("Пример: /rag compare машинное обучение")
            println()
            return
        }

        val stats = ragService!!.getIndexStats()
        if (stats.totalChunks == 0L) {
            println("Индекс пуст. Сначала выполните /rag index")
            println()
            return
        }

        if (!ragService.hasReranker()) {
            println("Реранкер не инициализирован.")
            println("Сравнение недоступно.")
            println()
            return
        }

        println("═".repeat(60))
        println("СРАВНЕНИЕ РЕЗУЛЬТАТОВ: С РЕРАНКИНГОМ vs БЕЗ")
        println("═".repeat(60))
        println("Запрос: \"$query\"")
        println("Порог отсечения: %.0f%%".format(context.state.rerankerThreshold * 100))
        println("Метод реранкинга: ${context.state.rerankerMethod}")
        println()

        try {
            val config = createRerankerConfig(context)
            val comparison = ragService.compareResults(query, topK = 5, rerankerConfig = config)

            if (comparison == null) {
                println("Ошибка: не удалось выполнить сравнение")
                return
            }

            // Показываем результаты БЕЗ реранкинга
            println("─".repeat(60))
            println("📋 БЕЗ РЕРАНКИНГА (исходные результаты по cosine similarity):")
            println("─".repeat(60))

            if (comparison.withoutReranking.isEmpty()) {
                println("Ничего не найдено")
            } else {
                comparison.withoutReranking.take(5).forEachIndexed { index, result ->
                    val score = "%.1f%%".format(result.similarity * 100)
                    println("[${index + 1}] $score - ${result.chunk.sourceFile}")
                    println("    ${result.chunk.content.take(100)}...")
                }
            }
            println()

            // Показываем результаты С реранкингом
            println("─".repeat(60))
            println("🔄 С РЕРАНКИНГОМ (после фильтрации и переранжирования):")
            println("─".repeat(60))

            val filtered = comparison.withReranking.filter { !it.wasFiltered }
            if (filtered.isEmpty()) {
                println("Все результаты отфильтрованы (порог: %.0f%%)".format(config.threshold * 100))
                println("Попробуйте понизить порог: /rag threshold 0.3")
            } else {
                filtered.take(5).forEachIndexed { index, result ->
                    val newScore = "%.1f%%".format(result.rerankedScore * 100)
                    val oldScore = "%.1f%%".format(result.originalScore * 100)
                    val change = result.rerankedScore - result.originalScore
                    val changeStr = if (change >= 0) "↑+%.1f%%".format(change * 100) else "↓%.1f%%".format(change * 100)

                    println("[${index + 1}] $newScore (было $oldScore, $changeStr) - ${result.original.chunk.sourceFile}")
                    println("    ${result.original.chunk.content.take(100)}...")
                }
            }
            println()

            // Показываем статистику
            println("─".repeat(60))
            println("📊 СТАТИСТИКА:")
            println("─".repeat(60))
            println("Всего кандидатов: ${comparison.withoutReranking.size}")
            println("Отфильтровано: ${comparison.filteredCount}")
            println("Осталось после фильтрации: ${filtered.size}")
            println("Изменили позицию: ${comparison.reorderedCount}")

            // Показываем отфильтрованные
            val filteredOut = comparison.withReranking.filter { it.wasFiltered }
            if (filteredOut.isNotEmpty()) {
                println()
                println("❌ Отфильтрованные (скор < %.0f%%):".format(config.threshold * 100))
                filteredOut.take(3).forEach { result ->
                    val score = "%.1f%%".format(result.rerankedScore * 100)
                    println("   $score - ${result.original.chunk.sourceFile}")
                }
                if (filteredOut.size > 3) {
                    println("   ... и ещё ${filteredOut.size - 3}")
                }
            }

            println()
            println("═".repeat(60))

        } catch (e: Exception) {
            println("Ошибка сравнения: ${e.message}")
        }
        println()
    }

    /**
     * Установка порога отсечения для реранкинга.
     */
    private fun setThreshold(args: String, context: CommandContext) {
        if (args.isBlank()) {
            println("Текущий порог отсечения: %.0f%%".format(context.state.rerankerThreshold * 100))
            println()
            println("Использование: /rag threshold <0.0-1.0>")
            println("Примеры:")
            println("  /rag threshold 0.3  - низкий порог (больше результатов)")
            println("  /rag threshold 0.5  - средний порог")
            println("  /rag threshold 0.7  - высокий порог (только релевантные)")
            println()
            return
        }

        val value = args.toFloatOrNull()
        if (value == null || value < 0f || value > 1f) {
            println("Ошибка: порог должен быть числом от 0.0 до 1.0")
            println("Пример: /rag threshold 0.4")
            println()
            return
        }

        val oldThreshold = context.state.rerankerThreshold
        context.state.rerankerThreshold = value
        println("Порог отсечения изменён: %.0f%% → %.0f%%".format(oldThreshold * 100, value * 100))

        // Даём рекомендации
        when {
            value < 0.3f -> println("⚠️ Низкий порог: будет много результатов, включая слабо релевантные")
            value > 0.6f -> println("⚠️ Высокий порог: только очень релевантные результаты пройдут фильтр")
            else -> println("✓ Оптимальный диапазон для большинства случаев")
        }
        println()
    }

    /**
     * Включение/выключение реранкинга.
     */
    private fun toggleReranker(args: String, context: CommandContext) {
        when (args.lowercase()) {
            "on" -> {
                context.state.rerankerEnabled = true
                if (ragService!!.hasReranker()) {
                    println("✓ Реранкинг включён")
                    println("Метод: ${context.state.rerankerMethod}")
                    println("Порог: %.0f%%".format(context.state.rerankerThreshold * 100))
                } else {
                    println("⚠️ Реранкинг включён, но реранкер не инициализирован")
                    println("Будет использоваться обычный поиск")
                }
            }
            "off" -> {
                context.state.rerankerEnabled = false
                println("○ Реранкинг выключен")
                println("Будет использоваться только cosine similarity")
            }
            else -> {
                val status = if (context.state.rerankerEnabled) "включён" else "выключен"
                println("Реранкинг сейчас: $status")
                println()
                println("Использование:")
                println("  /rag reranker on   - включить реранкинг")
                println("  /rag reranker off  - выключить реранкинг")
            }
        }
        println()
    }

    /**
     * Установка метода реранкинга.
     */
    private fun setMethod(args: String, context: CommandContext) {
        val validMethods = listOf("cross", "llm", "keyword")

        if (args.isBlank() || args.lowercase() !in validMethods) {
            println("Текущий метод реранкинга: ${context.state.rerankerMethod}")
            println()
            println("Доступные методы:")
            println("  cross   - кросс-кодирование через эмбеддинги (рекомендуется)")
            println("            Создаёт эмбеддинг пары query+doc и сравнивает")
            println("            Баланс скорости и качества")
            println()
            println("  llm     - оценка релевантности через LLM")
            println("            Просит LLM оценить релевантность по шкале 0-10")
            println("            Самый точный, но медленный")
            println()
            println("  keyword - гибридный метод: cosine + ключевые слова")
            println("            Добавляет буст за совпадение ключевых слов")
            println("            Самый быстрый")
            println()
            println("Использование: /rag method <cross|llm|keyword>")
            println()
            return
        }

        val newMethod = args.lowercase()
        val oldMethod = context.state.rerankerMethod
        context.state.rerankerMethod = newMethod

        val methodDescription = when (newMethod) {
            "cross" -> "кросс-кодирование"
            "llm" -> "LLM-оценка"
            "keyword" -> "гибридный (keyword)"
            else -> newMethod
        }

        println("Метод реранкинга изменён: $oldMethod → $newMethod")
        println("Описание: $methodDescription")
        println()
    }

    /**
     * Создание конфигурации реранкера на основе текущего состояния.
     */
    private fun createRerankerConfig(context: CommandContext): RerankerConfig {
        val method = when (context.state.rerankerMethod.lowercase()) {
            "cross" -> RerankerMethod.CROSS_ENCODER
            "llm" -> RerankerMethod.LLM_SCORING
            "keyword" -> RerankerMethod.KEYWORD_HYBRID
            else -> RerankerMethod.CROSS_ENCODER
        }

        return RerankerConfig(
            method = method,
            threshold = context.state.rerankerThreshold,
            useKeywordBoost = true,
            maxCandidates = 10
        )
    }
}