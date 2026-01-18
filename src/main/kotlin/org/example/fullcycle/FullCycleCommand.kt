package org.example.fullcycle

import org.example.app.commands.Command
import org.example.app.commands.CommandContext
import org.example.app.commands.CommandResult
import org.example.data.network.LlmClient
import org.example.data.rag.RagService

/**
 * Команда для запуска Full-Cycle Pipeline.
 *
 * Полный цикл:
 * 1. Анализ задачи
 * 2. Генерация плана
 * 3. Внесение изменений
 * 4. Git add/commit/push
 * 5. Создание PR
 * 6. Self-review с итерациями
 * 7. Ожидание CI
 * 8. Merge в main
 *
 * Использование:
 *   /full-cycle <описание задачи>
 *   /fcycle <описание задачи>
 *
 * Примеры:
 *   /full-cycle добавить кнопку выхода в настройках
 *   /fcycle исправить баг с отображением списка
 */
class FullCycleCommand(
    private val llmClient: LlmClient,
    private val ragService: RagService? = null
) : Command {

    override fun matches(input: String): Boolean {
        val trimmed = input.trim().lowercase()
        return trimmed.startsWith("/full-cycle") ||
               trimmed.startsWith("/fullcycle") ||
               trimmed.startsWith("/fcycle") ||
               isNaturalLanguageFullCycle(trimmed)
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        // Извлекаем описание задачи
        val taskDescription = extractTaskDescription(input)

        if (taskDescription.isBlank()) {
            printUsage()
            return CommandResult.Continue
        }

        println("\n" + "=".repeat(60))
        println("FULL-CYCLE PIPELINE")
        println("=".repeat(60))
        println("\nЗадача: $taskDescription")

        // Проверяем наличие GitHub токена
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("APPLICATION_GITHUB_TOKEN")
            ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")

        if (githubToken.isNullOrBlank()) {
            printError("GITHUB_TOKEN не установлен!")
            printError("Установите переменную окружения GITHUB_TOKEN или GITHUB_PERSONAL_ACCESS_TOKEN")
            return CommandResult.Continue
        }

        // Создаём сервис пайплайна
        val pipelineService = FullCyclePipelineService(
            llmClient = llmClient,
            ragService = ragService,
            githubToken = githubToken,
            config = PipelineConfig(
                maxReviewIterations = 10,
                maxCIRetries = 5,
                autoMerge = true,
                requireCIPass = true
            )
        )

        // Настраиваем отображение прогресса
        pipelineService.onProgress = { message ->
            println(message)
        }

        // Выполняем пайплайн
        val report = pipelineService.executeFullCycle(
            taskDescription = taskDescription,
            confirmPlan = { plan ->
                // Запрашиваем подтверждение у пользователя
                println("\n" + "-".repeat(40))
                print("Начать выполнение? (y/n): ")
                System.out.flush()

                val response = readlnOrNull()?.trim()?.lowercase()
                response == "y" || response == "yes" || response == "да"
            }
        )

        // Показываем итог
        if (!report.success) {
            println("\n" + "-".repeat(60))
            println("❌ Пайплайн завершился с ошибками:")
            report.errors.forEach { error ->
                println("   - $error")
            }
        }

        return CommandResult.Continue
    }

    /**
     * Проверяет, является ли ввод командой full-cycle на естественном языке
     */
    private fun isNaturalLanguageFullCycle(input: String): Boolean {
        val patterns = listOf(
            // Русские паттерны
            Regex("""(сделай|реализуй|добавь|исправь|создай).*(и|затем).*(залей|смержи|запушь|закоммить)""", RegexOption.IGNORE_CASE),
            Regex("""(сделай|реализуй|добавь).*(в\s*main|в\s*мастер)""", RegexOption.IGNORE_CASE),
            Regex("""(полный\s*цикл|фул\s*сайкл)""", RegexOption.IGNORE_CASE),
            // Английские паттерны
            Regex("""(implement|add|fix|create).*(and|then).*(merge|push|commit)""", RegexOption.IGNORE_CASE),
            Regex("""(implement|add|fix).*(to|into)\s*main""", RegexOption.IGNORE_CASE),
            Regex("""full\s*cycle""", RegexOption.IGNORE_CASE)
        )

        return patterns.any { it.containsMatchIn(input) }
    }

    /**
     * Извлекает описание задачи из ввода
     */
    private fun extractTaskDescription(input: String): String {
        val trimmed = input.trim()

        // Если это команда, убираем префикс
        val withoutPrefix = when {
            trimmed.startsWith("/full-cycle", ignoreCase = true) ->
                trimmed.removePrefix("/full-cycle").removePrefix("/Full-cycle").removePrefix("/FULL-CYCLE")
            trimmed.startsWith("/fullcycle", ignoreCase = true) ->
                trimmed.removePrefix("/fullcycle").removePrefix("/Fullcycle").removePrefix("/FULLCYCLE")
            trimmed.startsWith("/fcycle", ignoreCase = true) ->
                trimmed.removePrefix("/fcycle").removePrefix("/Fcycle").removePrefix("/FCYCLE")
            else -> trimmed
        }

        // Убираем кавычки если есть
        return withoutPrefix
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    private fun printUsage() {
        println("""

            Full-Cycle Pipeline - полный цикл автоматизации

            Использование:
              /full-cycle <описание задачи>
              /fcycle <описание задачи>

            Или естественным языком:
              сделай <задачу> и залей в main
              реализуй <фичу> и смержи

            Примеры:
              /full-cycle добавить кнопку выхода в настройках
              /fcycle исправить баг с отображением списка пользователей
              сделай валидацию email в форме регистрации и залей в main

            Пайплайн автоматически:
              1. Проанализирует задачу
              2. Создаст план изменений (с подтверждением)
              3. Внесёт изменения в код
              4. Создаст ветку, коммит и PR
              5. Сделает self-review и исправит замечания
              6. Дождётся прохождения CI
              7. Замержит PR в main

        """.trimIndent())
    }

    private fun printError(message: String) {
        println("\u001B[31m✗ $message\u001B[0m")
    }
}
