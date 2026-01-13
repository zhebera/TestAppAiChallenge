package org.example.mcp.server.github

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * MCP сервер для работы с GitHub и расширенными git операциями.
 *
 * Инструменты:
 * - git_push: пушит текущую ветку на remote
 * - git_push_new_branch: создаёт новую ветку и пушит
 * - create_pull_request: создаёт PR через GitHub CLI (gh)
 * - get_repo_info: информация о текущем репозитории
 */
class GitHubMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val workDir = File(System.getProperty("user.dir"))

    fun run() {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                if (response.isNotEmpty()) {
                    println(json.encodeToString(response))
                    System.out.flush()
                }
            } catch (e: Exception) {
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    }
                }
                println(json.encodeToString(errorResponse))
                System.out.flush()
            }
        }
    }

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> JsonObject(emptyMap())
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "github-extended-mcp")
                    put("version", "1.0.0")
                }
            }
        }
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    // git_push
                    addJsonObject {
                        put("name", "git_push")
                        put("description", "Запушить текущую ветку на remote (origin). Используй когда пользователь просит запушить изменения на GitHub.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("force") {
                                    put("type", "boolean")
                                    put("description", "Force push (--force). Использовать осторожно!")
                                    put("default", false)
                                }
                                putJsonObject("set_upstream") {
                                    put("type", "boolean")
                                    put("description", "Установить upstream (-u)")
                                    put("default", true)
                                }
                            }
                        }
                    }

                    // git_push_new_branch
                    addJsonObject {
                        put("name", "git_push_new_branch")
                        put("description", "Создать новую ветку от текущей и запушить её на remote. Используй когда нужно создать feature branch.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("branch_name") {
                                    put("type", "string")
                                    put("description", "Название новой ветки")
                                }
                            }
                            putJsonArray("required") { add("branch_name") }
                        }
                    }

                    // create_pull_request
                    addJsonObject {
                        put("name", "create_pull_request")
                        put("description", "Создать Pull Request на GitHub. Требует установленный gh CLI или GITHUB_TOKEN. Используй после push для создания PR.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("title") {
                                    put("type", "string")
                                    put("description", "Заголовок PR")
                                }
                                putJsonObject("body") {
                                    put("type", "string")
                                    put("description", "Описание PR (поддерживает Markdown)")
                                }
                                putJsonObject("base") {
                                    put("type", "string")
                                    put("description", "Базовая ветка (по умолчанию main)")
                                    put("default", "main")
                                }
                                putJsonObject("draft") {
                                    put("type", "boolean")
                                    put("description", "Создать как draft PR")
                                    put("default", false)
                                }
                            }
                            putJsonArray("required") { add("title") }
                        }
                    }

                    // get_repo_info
                    addJsonObject {
                        put("name", "get_repo_info")
                        put("description", "Получить информацию о текущем git репозитории: remote URL, текущая ветка, owner/repo.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // review_and_comment_pr
                    addJsonObject {
                        put("name", "review_and_comment_pr")
                        put("description", "Сделать AI ревью PR и оставить комментарий. Используй когда пользователь просит сделать ревью PR.")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("pr_number") {
                                    put("type", "integer")
                                    put("description", "Номер PR для ревью. Если не указан, ревью последнего созданного PR.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())

        val result = when (toolName) {
            "git_push" -> gitPush(arguments)
            "git_push_new_branch" -> gitPushNewBranch(arguments)
            "create_pull_request" -> createPullRequest(arguments)
            "get_repo_info" -> getRepoInfo()
            "review_and_comment_pr" -> reviewAndCommentPr(arguments)
            else -> "Неизвестный инструмент: $toolName"
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
    }

    // === Tool implementations ===

    private fun gitPush(arguments: JsonObject): String {
        val force = arguments["force"]?.jsonPrimitive?.booleanOrNull ?: false
        val setUpstream = arguments["set_upstream"]?.jsonPrimitive?.booleanOrNull ?: true

        // Получаем текущую ветку
        val branch = runCommand("git", "branch", "--show-current").trim()
        if (branch.isBlank()) {
            return "Ошибка: не удалось определить текущую ветку"
        }

        // Формируем команду push
        val cmd = mutableListOf("git", "push")
        if (setUpstream) {
            cmd.addAll(listOf("-u", "origin", branch))
        } else {
            cmd.addAll(listOf("origin", branch))
        }
        if (force) {
            cmd.add("--force")
        }

        val result = runCommand(*cmd.toTypedArray())

        return if (result.contains("error") || result.contains("fatal")) {
            "Ошибка push:\n$result"
        } else {
            buildString {
                appendLine("✅ Ветка '$branch' успешно запушена на origin")
                if (result.isNotBlank()) {
                    appendLine()
                    appendLine(result)
                }
            }
        }
    }

    private fun gitPushNewBranch(arguments: JsonObject): String {
        val branchName = arguments["branch_name"]?.jsonPrimitive?.content
            ?: return "Ошибка: не указано название ветки"

        // Создаём ветку
        val createResult = runCommand("git", "checkout", "-b", branchName)
        if (createResult.contains("fatal") || createResult.contains("error")) {
            return "Ошибка создания ветки:\n$createResult"
        }

        // Пушим
        val pushResult = runCommand("git", "push", "-u", "origin", branchName)

        return if (pushResult.contains("error") || pushResult.contains("fatal")) {
            "Ветка создана, но ошибка push:\n$pushResult"
        } else {
            buildString {
                appendLine("✅ Создана и запушена ветка: $branchName")
                appendLine()
                appendLine("Для создания PR выполните:")
                appendLine("  create_pull_request с title")
            }
        }
    }

    private fun createPullRequest(arguments: JsonObject): String {
        val title = arguments["title"]?.jsonPrimitive?.content
            ?: return "Ошибка: не указан заголовок PR"
        val body = arguments["body"]?.jsonPrimitive?.content ?: ""
        val base = arguments["base"]?.jsonPrimitive?.content ?: "main"
        val draft = arguments["draft"]?.jsonPrimitive?.booleanOrNull ?: false

        // Проверяем наличие gh CLI
        val ghCheck = runCommand("which", "gh")
        val useGhCli = ghCheck.isNotBlank() && !ghCheck.contains("not found")

        return if (useGhCli) {
            createPrWithGhCli(title, body, base, draft)
        } else {
            createPrWithApi(title, body, base, draft)
        }
    }

    private fun createPrWithGhCli(title: String, body: String, base: String, draft: Boolean): String {
        val cmd = mutableListOf("gh", "pr", "create", "--title", title, "--base", base)

        if (body.isNotBlank()) {
            cmd.addAll(listOf("--body", body))
        } else {
            cmd.add("--fill")
        }

        if (draft) {
            cmd.add("--draft")
        }

        val result = runCommand(*cmd.toTypedArray())

        return if (result.contains("https://github.com")) {
            val url = result.lines().find { it.contains("github.com") }?.trim() ?: result
            buildString {
                appendLine("✅ Pull Request создан!")
                appendLine()
                appendLine("URL: $url")
            }
        } else if (result.contains("error") || result.contains("fatal")) {
            "Ошибка создания PR:\n$result"
        } else {
            "PR создан:\n$result"
        }
    }

    private fun createPrWithApi(title: String, body: String, base: String, draft: Boolean): String {
        // Получаем информацию о репозитории
        val remoteUrl = runCommand("git", "remote", "get-url", "origin")
        val pattern = Regex("""github\.com[:/]([^/]+)/([^/.]+)""")
        val match = pattern.find(remoteUrl)
            ?: return "Ошибка: не удалось определить owner/repo из remote URL"

        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val head = runCommand("git", "branch", "--show-current").trim()

        val token = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
            ?: return "Ошибка: GITHUB_TOKEN не установлен и gh CLI не найден"

        // Используем curl для создания PR
        val jsonBody = buildJsonObject {
            put("title", title)
            put("body", body.ifBlank { "Created via MCP" })
            put("head", head)
            put("base", base)
            put("draft", draft)
        }

        val curlResult = runCommand(
            "curl", "-s", "-X", "POST",
            "-H", "Authorization: token $token",
            "-H", "Accept: application/vnd.github.v3+json",
            "-d", json.encodeToString(jsonBody),
            "https://api.github.com/repos/$owner/$repo/pulls"
        )

        return try {
            val response = json.parseToJsonElement(curlResult).jsonObject
            val prUrl = response["html_url"]?.jsonPrimitive?.content
            val prNumber = response["number"]?.jsonPrimitive?.int

            if (prUrl != null) {
                buildString {
                    appendLine("✅ Pull Request #$prNumber создан!")
                    appendLine()
                    appendLine("URL: $prUrl")
                }
            } else {
                val error = response["message"]?.jsonPrimitive?.content ?: curlResult
                "Ошибка создания PR: $error"
            }
        } catch (e: Exception) {
            "Ошибка парсинга ответа: ${e.message}\n$curlResult"
        }
    }

    private fun getRepoInfo(): String {
        val remoteUrl = runCommand("git", "remote", "get-url", "origin")
        val branch = runCommand("git", "branch", "--show-current").trim()
        val status = runCommand("git", "status", "--short")

        val pattern = Regex("""github\.com[:/]([^/]+)/([^/.]+)""")
        val match = pattern.find(remoteUrl)

        return buildString {
            appendLine("## Информация о репозитории")
            appendLine()
            if (match != null) {
                appendLine("**Owner:** ${match.groupValues[1]}")
                appendLine("**Repo:** ${match.groupValues[2]}")
            }
            appendLine("**Remote URL:** ${remoteUrl.trim()}")
            appendLine("**Текущая ветка:** $branch")
            appendLine()
            if (status.isNotBlank()) {
                appendLine("**Изменения:**")
                appendLine("```")
                appendLine(status)
                appendLine("```")
            } else {
                appendLine("**Изменения:** нет (working tree clean)")
            }
        }
    }

    private fun reviewAndCommentPr(arguments: JsonObject): String {
        val prNumber = arguments["pr_number"]?.jsonPrimitive?.intOrNull

        // Получаем информацию о репозитории
        val remoteUrl = runCommand("git", "remote", "get-url", "origin")
        val pattern = Regex("""github\.com[:/]([^/]+)/([^/.]+)""")
        val match = pattern.find(remoteUrl)
            ?: return "Ошибка: не удалось определить owner/repo"

        val owner = match.groupValues[1]
        val repo = match.groupValues[2]

        // Если номер PR не указан, пытаемся найти последний PR для текущей ветки
        val targetPr = prNumber ?: findLatestPrNumber(owner, repo)
            ?: return "Не найден PR для ревью. Укажите номер PR или сначала создайте PR."

        // Проверяем наличие необходимых токенов
        val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        if (anthropicKey.isNullOrBlank()) {
            return "Ошибка: ANTHROPIC_API_KEY не установлен. Ревью невозможно."
        }

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("APPLICATION_GITHUB_TOKEN")
            ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
        if (githubToken.isNullOrBlank()) {
            return "Ошибка: GITHUB_TOKEN не установлен. Ревью невозможно."
        }

        // Запускаем ревью через gradle task
        return try {
            val result = StringBuilder()
            result.appendLine("🔍 Запускаю AI ревью PR #$targetPr...")
            result.appendLine()

            // Запускаем PrReviewRunner через gradle
            val reviewProcess = ProcessBuilder(
                "./gradlew", "runPrReview",
                "--args=$owner $repo $targetPr",
                "--console=plain",
                "-q"
            )
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(reviewProcess.inputStream))
                .readText()

            val exitCode = reviewProcess.waitFor()

            if (exitCode == 0) {
                result.appendLine("✅ Ревью PR #$targetPr завершено!")
                result.appendLine()
                result.appendLine("Результат:")
                result.appendLine(output.take(3000)) // Ограничиваем вывод
                if (output.length > 3000) {
                    result.appendLine("... (вывод обрезан)")
                }
            } else {
                result.appendLine("⚠️ Ревью завершилось с кодом $exitCode")
                result.appendLine()
                result.appendLine(output.take(2000))
            }

            result.toString()
        } catch (e: Exception) {
            buildString {
                appendLine("❌ Ошибка запуска ревью: ${e.message}")
                appendLine()
                appendLine("Попробуйте запустить вручную:")
                appendLine("```")
                appendLine("/review-pr $owner/$repo $targetPr")
                appendLine("```")
            }
        }
    }

    private fun findLatestPrNumber(owner: String, repo: String): Int? {
        val token = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("APPLICATION_GITHUB_TOKEN")
            ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
            ?: return null

        val branch = runCommand("git", "branch", "--show-current").trim()

        val result = runCommand(
            "curl", "-s",
            "-H", "Authorization: token $token",
            "-H", "Accept: application/vnd.github.v3+json",
            "https://api.github.com/repos/$owner/$repo/pulls?head=$owner:$branch&state=open"
        )

        return try {
            val prs = json.parseToJsonElement(result).jsonArray
            prs.firstOrNull()?.jsonObject?.get("number")?.jsonPrimitive?.int
        } catch (e: Exception) {
            null
        }
    }

    private fun runCommand(vararg command: String): String {
        return try {
            val process = ProcessBuilder(*command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}

fun main() {
    GitHubMcpServer().run()
}
