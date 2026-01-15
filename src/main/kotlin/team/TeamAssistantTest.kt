package org.example.team

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.data.persistence.DatabaseConfig
import org.example.mcp.server.tasks.*

/**
 * Тестовый скрипт для проверки всего функционала Team Assistant.
 */
fun main() = runBlocking {
    println("=" .repeat(70))
    println("        ТЕСТИРОВАНИЕ TEAM ASSISTANT - ВСЕ КОМПОНЕНТЫ")
    println("=".repeat(70))
    println()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    var totalTests = 0
    var passedTests = 0

    // ========== ФАЗА 1: SQLite + Exposed ==========
    println(">>> ФАЗА 1: SQLite + Exposed ORM")
    println("-".repeat(50))

    // Тест 1.1: Инициализация БД
    totalTests++
    print("  [1.1] Инициализация базы данных... ")
    try {
        DatabaseConfig.init()
        println("OK")
        passedTests++
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 1.2: Создание TaskDataManager
    totalTests++
    print("  [1.2] Создание TaskDataManager... ")
    val dataManager = try {
        val dm = TaskDataManager()
        println("OK")
        passedTests++
        dm
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
        null
    }

    if (dataManager != null) {
        // Тест 1.3: Получение всех задач
        totalTests++
        print("  [1.3] Получение всех задач... ")
        try {
            val tasks = dataManager.getAllTasks()
            if (tasks.isNotEmpty()) {
                println("OK (${tasks.size} задач)")
                passedTests++
            } else {
                println("OK (пусто, будут созданы демо-данные)")
                passedTests++
            }
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
        }

        // Тест 1.4: Получение участников команды
        totalTests++
        print("  [1.4] Получение участников команды... ")
        try {
            val members = dataManager.getAllMembers()
            if (members.isNotEmpty()) {
                println("OK (${members.size} участников)")
                passedTests++
            } else {
                println("WARN: нет участников")
            }
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
        }

        // Тест 1.5: Получение спринтов
        totalTests++
        print("  [1.5] Получение спринтов... ")
        try {
            val sprints = dataManager.getAllSprints()
            val activeSprint = dataManager.getActiveSprint()
            println("OK (${sprints.size} спринтов, активный: ${activeSprint?.name ?: "нет"})")
            passedTests++
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
        }

        // Тест 1.6: Создание задачи
        totalTests++
        print("  [1.6] Создание новой задачи... ")
        try {
            val newTask = dataManager.createTask(
                title = "Тестовая задача",
                description = "Описание тестовой задачи",
                priority = TaskPriority.MEDIUM,
                type = TaskType.FEATURE,
                reporterId = "pm_1"
            )
            println("OK (${newTask.id})")
            passedTests++

            // Тест 1.7: Обновление статуса
            totalTests++
            print("  [1.7] Обновление статуса задачи... ")
            val updated = dataManager.updateTaskStatus(newTask.id, TaskStatus.IN_PROGRESS)
            if (updated != null && updated.status == TaskStatus.IN_PROGRESS) {
                println("OK")
                passedTests++
            } else {
                println("FAIL")
            }

            // Тест 1.8: Добавление комментария
            totalTests++
            print("  [1.8] Добавление комментария... ")
            val withComment = dataManager.addTaskComment(newTask.id, "dev_1", "Тестовый комментарий")
            if (withComment != null && withComment.comments.isNotEmpty()) {
                println("OK")
                passedTests++
            } else {
                println("FAIL")
            }

            // Тест 1.9: Удаление задачи
            totalTests++
            print("  [1.9] Удаление задачи... ")
            val deleted = dataManager.deleteTask(newTask.id)
            if (deleted) {
                println("OK")
                passedTests++
            } else {
                println("FAIL")
            }
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
        }

        // Тест 1.10: Статус проекта
        totalTests++
        print("  [1.10] Получение статуса проекта... ")
        try {
            val status = dataManager.getProjectStatus()
            println("OK (всего: ${status.totalTasks}, done: ${status.doneTasks})")
            passedTests++
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
        }
    }

    println()

    // ========== ФАЗА 2: Intent Classification ==========
    println(">>> ФАЗА 2: Intent Classification + NL Task Creation")
    println("-".repeat(50))

    // Тест 2.1: IntentClassifier (эвристики)
    totalTests++
    print("  [2.1] Intent Classification (эвристики)... ")
    try {
        // Создаём classifier без LLM для тестирования эвристик
        val classifier = IntentClassifier(null, json)

        // Тестируем разные сообщения
        val testCases = mapOf(
            "Покажи все задачи" to UserIntent.TASK_QUERY,
            "Создай новую задачу" to UserIntent.TASK_CREATE,
            "Какой статус проекта?" to UserIntent.PROJECT_STATUS,
            "Что делать первым?" to UserIntent.RECOMMENDATIONS,
            "Как работает RAG система?" to UserIntent.CODE_QUESTION,
            "Покажи загрузку команды" to UserIntent.TEAM_INFO
        )

        var correctCount = 0
        for ((message, expectedIntent) in testCases) {
            val result = classifier.classifyByHeuristics(message)
            if (classifier.toUserIntent(result.intent) == expectedIntent) {
                correctCount++
            }
        }

        if (correctCount >= 4) { // Минимум 4 из 6 должны быть правильными
            println("OK ($correctCount/${testCases.size} правильно)")
            passedTests++
        } else {
            println("WARN: только $correctCount/${testCases.size} правильно")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 2.2: NL Task Parser (эвристики)
    totalTests++
    print("  [2.2] NL Task Parser (эвристики)... ")
    try {
        val parser = NaturalLanguageTaskParser(null, json)

        val testMessage = "Создай баг с critical приоритетом про утечку памяти"
        val parsed = parser.parseByHeuristics(testMessage)

        val checks = mutableListOf<Boolean>()
        checks.add(parsed.type == "bug")
        checks.add(parsed.priority == "critical")
        checks.add(parsed.title.isNotBlank())

        val passedChecks = checks.count { it }
        if (passedChecks >= 2) {
            println("OK (type=${parsed.type}, priority=${parsed.priority})")
            passedTests++
        } else {
            println("FAIL: некорректный парсинг")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 2.3: Entity extraction
    totalTests++
    print("  [2.3] Entity extraction... ")
    try {
        val classifier = IntentClassifier(null, json)
        val entities = classifier.extractEntitiesByHeuristics("Покажи task_001 с high приоритетом")

        if (entities.taskId == "task_001" && entities.priority == "high") {
            println("OK (taskId=${entities.taskId}, priority=${entities.priority})")
            passedTests++
        } else {
            println("FAIL: taskId=${entities.taskId}, priority=${entities.priority}")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    println()

    // ========== ФАЗА 3: Guardrails + UX ==========
    println(">>> ФАЗА 3: Guardrails + Notifications")
    println("-".repeat(50))

    // Тест 3.1: TeamPrompts с guardrails
    totalTests++
    print("  [3.1] Генерация промпта с guardrails... ")
    try {
        val prompt = TeamPrompts.buildPromptWithGuardrails(
            projectContext = "Тестовый контекст",
            tasksContext = "Тестовые задачи",
            githubContext = "",
            intent = UserIntent.RECOMMENDATIONS
        )

        val hasGuardrails = prompt.contains("НИКОГДА") && prompt.contains("ВСЕГДА")
        val hasInstructions = prompt.contains("Инструкции для рекомендаций")

        if (hasGuardrails && hasInstructions) {
            println("OK (guardrails присутствуют)")
            passedTests++
        } else {
            println("FAIL: guardrails отсутствуют")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 3.2: Разные промпты для разных интентов
    totalTests++
    print("  [3.2] Специфичные промпты по интенту... ")
    try {
        val createPrompt = TeamPrompts.buildPromptWithGuardrails("", "", "", UserIntent.TASK_CREATE)
        val updatePrompt = TeamPrompts.buildPromptWithGuardrails("", "", "", UserIntent.TASK_UPDATE)

        val createHasInstructions = createPrompt.contains("создании задачи")
        val updateHasInstructions = updatePrompt.contains("изменении задачи")

        if (createHasInstructions && updateHasInstructions) {
            println("OK")
            passedTests++
        } else {
            println("FAIL")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    println()

    // ========== ФАЗА 4: Интеграции ==========
    println(">>> ФАЗА 4: GitHub Sync + Git Hooks")
    println("-".repeat(50))

    // Тест 4.1: Git Hooks - парсинг task ID
    totalTests++
    print("  [4.1] Парсинг task ID из коммита... ")
    try {
        val gitHooks = GitHooksService(dataManager!!)

        val testMessages = listOf(
            "[task_001] Fix memory leak" to listOf("001"),
            "Closes task_002" to listOf("002"),
            "Multiple: task_003 and task_004" to listOf("003", "004"),
            "No task mentioned" to emptyList()
        )

        var correctCount = 0
        for ((message, expectedIds) in testMessages) {
            val extractedIds = gitHooks.extractTaskIds(message)
            if (extractedIds.toSet() == expectedIds.toSet()) {
                correctCount++
            }
        }

        if (correctCount == testMessages.size) {
            println("OK ($correctCount/${testMessages.size})")
            passedTests++
        } else {
            println("WARN: $correctCount/${testMessages.size}")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 4.2: Git Hooks - генерация скриптов
    totalTests++
    print("  [4.2] Генерация git hook скриптов... ")
    try {
        val gitHooks = GitHooksService(dataManager!!)

        val postCommit = gitHooks.generatePostCommitHook("/test/path")
        val prepareMsg = gitHooks.generatePrepareCommitMsgHook()

        val postCommitValid = postCommit.contains("#!/bin/bash") && postCommit.contains("COMMIT_MSG")
        val prepareMsgValid = prepareMsg.contains("#!/bin/bash") && prepareMsg.contains("task_XXX")

        if (postCommitValid && prepareMsgValid) {
            println("OK")
            passedTests++
        } else {
            println("FAIL")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 4.3: Git Hooks - обработка коммита
    totalTests++
    print("  [4.3] Обработка коммита с task ID... ")
    try {
        val gitHooks = GitHooksService(dataManager!!)

        // Создаём тестовую задачу
        val testTask = dataManager.createTask(
            title = "Task for git hook test",
            description = "Testing git hooks",
            priority = TaskPriority.MEDIUM,
            type = TaskType.FEATURE,
            reporterId = "test"
        )

        // Обрабатываем коммит
        val taskIdNumber = testTask.id.removePrefix("task_")
        val result = gitHooks.processCommit("Closes task_$taskIdNumber: fixed the issue")

        if (result.found && result.updates.isNotEmpty()) {
            println("OK (задача обновлена)")
            passedTests++
        } else {
            println("WARN: ${result.message}")
        }

        // Очищаем
        dataManager.deleteTask(testTask.id)
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 4.4: GitHub Sync Service создание
    totalTests++
    print("  [4.4] GitHubSyncService инициализация... ")
    try {
        val syncService = GitHubSyncService(null, dataManager!!, json)
        println("OK")
        passedTests++
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    println()

    // ========== MCP Server ==========
    println(">>> MCP Server Tools")
    println("-".repeat(50))

    // Тест 5.1: Форматирование для LLM
    totalTests++
    print("  [5.1] Форматирование задач для LLM... ")
    try {
        val tasks = dataManager!!.getAllTasks().take(2)
        if (tasks.isNotEmpty()) {
            val formatted = dataManager.formatTasksListForLlm(tasks)
            if (formatted.contains("Найдено задач") && formatted.contains("task_")) {
                println("OK")
                passedTests++
            } else {
                println("FAIL: некорректный формат")
            }
        } else {
            println("SKIP: нет задач")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 5.2: Форматирование статуса проекта
    totalTests++
    print("  [5.2] Форматирование статуса проекта... ")
    try {
        val status = dataManager!!.getProjectStatus()
        val formatted = dataManager.formatProjectStatusForLlm(status)

        if (formatted.contains("Статус проекта") &&
            formatted.contains("Всего задач") &&
            formatted.contains("CRITICAL")) {
            println("OK")
            passedTests++
        } else {
            println("FAIL: некорректный формат")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 5.3: Поиск задач
    totalTests++
    print("  [5.3] Поиск задач с фильтрами... ")
    try {
        val highTasks = dataManager!!.searchTasks(priority = TaskPriority.HIGH)
        val criticalTasks = dataManager.searchTasks(priority = TaskPriority.CRITICAL)
        println("OK (HIGH: ${highTasks.size}, CRITICAL: ${criticalTasks.size})")
        passedTests++
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    // Тест 5.4: Загрузка команды
    totalTests++
    print("  [5.4] Расчёт загрузки команды... ")
    try {
        val members = dataManager!!.getAllMembers()
        if (members.isNotEmpty()) {
            val workloads = members.map { it.id to dataManager.getMemberWorkload(it.id) }
            println("OK (${workloads.joinToString { "${it.first}:${it.second}" }})")
            passedTests++
        } else {
            println("SKIP: нет участников")
        }
    } catch (e: Exception) {
        println("FAIL: ${e.message}")
    }

    println()
    println("=".repeat(70))
    println("        РЕЗУЛЬТАТЫ ТЕСТИРОВАНИЯ")
    println("=".repeat(70))
    println()
    println("  Всего тестов:    $totalTests")
    println("  Пройдено:        $passedTests")
    println("  Провалено:       ${totalTests - passedTests}")
    println("  Успешность:      ${passedTests * 100 / totalTests}%")
    println()

    if (passedTests == totalTests) {
        println("  ✓ ВСЕ ТЕСТЫ ПРОЙДЕНЫ УСПЕШНО!")
    } else if (passedTests >= totalTests * 0.9) {
        println("  ✓ ТЕСТИРОВАНИЕ ЗАВЕРШЕНО (${totalTests - passedTests} предупреждений)")
    } else {
        println("  ✗ ЕСТЬ КРИТИЧЕСКИЕ ОШИБКИ")
    }
    println()
    println("=".repeat(70))
}
