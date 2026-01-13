package org.example.prreview

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.example.data.mcp.McpClient
import org.example.data.mcp.McpClientFactory
import org.example.data.mcp.McpException
import org.example.data.network.LlmClient
import org.example.data.network.StreamEvent
import org.example.data.rag.RagService
import org.example.data.dto.LlmRequest
import org.example.data.dto.LlmResponse
import org.example.domain.models.LlmMessage
import org.example.domain.models.ChatRole

/**
 * PR Review Service - анализирует Pull Request и генерирует ревью с замечаниями.
 *
 * Использует:
 * - GitHub MCP Server для получения информации о PR (diff, файлы, описание)
 * - RAG для обогащения контекста документацией и кодом проекта
 * - LLM для анализа и генерации замечаний
 */
class PrReviewService(
    private val llmClient: LlmClient,
    private val ragService: RagService? = null,
    private val githubToken: String? = null
) {
    private val json = McpClientFactory.createJson()
    private var githubClient: McpClient? = null

    /**
     * Результат ревью PR
     */
    @Serializable
    data class ReviewResult(
        val prNumber: Int,
        val prTitle: String,
        val summary: String,
        val comments: List<ReviewComment>,
        val overallScore: String, // "APPROVE", "REQUEST_CHANGES", "COMMENT"
        val stats: ReviewStats
    )

    @Serializable
    data class ReviewComment(
        val file: String,
        val line: Int? = null,
        val severity: String, // "critical", "warning", "suggestion", "nitpick"
        val message: String,
        val codeSnippet: String? = null
    )

    @Serializable
    data class ReviewStats(
        val filesChanged: Int,
        val additions: Int,
        val deletions: Int,
        val commentsGenerated: Int
    )

    /**
     * Подключиться к GitHub MCP Server
     */
    suspend fun connect(): Boolean {
        return try {
            githubClient = McpClientFactory.createGitHubClient(githubToken)
            githubClient?.connect()
            true
        } catch (e: Exception) {
            println("Ошибка подключения к GitHub: ${e.message}")
            false
        }
    }

    /**
     * Отключиться от GitHub MCP Server
     */
    fun disconnect() {
        githubClient?.disconnect()
        githubClient = null
    }

    /**
     * Выполнить ревью PR
     * @param owner Владелец репозитория
     * @param repo Название репозитория
     * @param prNumber Номер PR
     * @param useRag Использовать RAG для контекста
     */
    suspend fun reviewPr(
        owner: String,
        repo: String,
        prNumber: Int,
        useRag: Boolean = true
    ): ReviewResult {
        if (githubClient == null || !githubClient!!.isConnected) {
            if (!connect()) {
                throw McpException("Не удалось подключиться к GitHub")
            }
        }

        // 1. Получаем информацию о PR
        val prInfo = getPrInfo(owner, repo, prNumber)

        // 2. Получаем diff PR
        val diff = getPrDiff(owner, repo, prNumber)

        // 3. Получаем список изменённых файлов
        val changedFiles = getPrFiles(owner, repo, prNumber)

        // 4. Обогащаем контекст через RAG (если включён)
        val ragContext = if (useRag && ragService != null) {
            buildRagContext(prInfo, changedFiles)
        } else ""

        // 5. Формируем промпт для LLM
        val reviewPrompt = buildReviewPrompt(prInfo, diff, changedFiles, ragContext)

        // 6. Получаем ревью от LLM
        val reviewResponse = getReviewFromLlm(reviewPrompt)

        // 7. Парсим результат
        return parseReviewResponse(reviewResponse, prNumber, prInfo)
    }

    /**
     * Стриминг ревью PR (для интерактивного отображения)
     */
    fun reviewPrStream(
        prInfo: String,
        diff: String,
        changedFiles: List<String>,
        ragContext: String
    ): Flow<StreamEvent> {
        val reviewPrompt = buildReviewPrompt(prInfo, diff, changedFiles, ragContext)

        val request = LlmRequest(
            model = llmClient.model,
            messages = listOf(
                LlmMessage(role = ChatRole.USER, content = reviewPrompt)
            ),
            systemPrompt = SYSTEM_PROMPT_CODE_REVIEW,
            temperature = 0.3,
            maxTokens = 4096
        )

        return llmClient.sendStream(request)
    }

    /**
     * Опубликовать комментарий к PR
     */
    suspend fun postReviewComment(
        owner: String,
        repo: String,
        prNumber: Int,
        body: String
    ): Boolean {
        return try {
            githubClient?.callTool(
                "create_issue_comment",
                mapOf(
                    "owner" to JsonPrimitive(owner),
                    "repo" to JsonPrimitive(repo),
                    "issue_number" to JsonPrimitive(prNumber),
                    "body" to JsonPrimitive(body)
                )
            )
            true
        } catch (e: Exception) {
            println("Ошибка публикации комментария: ${e.message}")
            false
        }
    }

    /**
     * Опубликовать inline комментарий к конкретной строке
     */
    suspend fun postInlineComment(
        owner: String,
        repo: String,
        prNumber: Int,
        commitId: String,
        path: String,
        line: Int,
        body: String
    ): Boolean {
        return try {
            githubClient?.callTool(
                "create_pull_request_review",
                mapOf(
                    "owner" to JsonPrimitive(owner),
                    "repo" to JsonPrimitive(repo),
                    "pull_number" to JsonPrimitive(prNumber),
                    "commit_id" to JsonPrimitive(commitId),
                    "event" to JsonPrimitive("COMMENT"),
                    "comments" to buildJsonArray {
                        addJsonObject {
                            put("path", path)
                            put("line", line)
                            put("body", body)
                        }
                    }
                )
            )
            true
        } catch (e: Exception) {
            println("Ошибка публикации inline комментария: ${e.message}")
            false
        }
    }

    /**
     * Опубликовать полное ревью с несколькими inline комментариями
     */
    suspend fun submitReview(
        owner: String,
        repo: String,
        prNumber: Int,
        result: ReviewResult
    ): Boolean {
        // Получаем последний коммит PR для inline комментариев
        val commitId = getLatestCommitId(owner, repo, prNumber)

        // Формируем тело основного комментария
        val reviewBody = formatReviewBody(result)

        // Формируем inline комментарии
        val inlineComments = result.comments.filter { it.line != null }.map { comment ->
            buildJsonObject {
                put("path", comment.file)
                put("line", comment.line!!)
                put("body", formatInlineComment(comment))
            }
        }

        return try {
            if (inlineComments.isNotEmpty() && commitId != null) {
                // Создаём review с inline комментариями
                githubClient?.callTool(
                    "create_pull_request_review",
                    mapOf(
                        "owner" to JsonPrimitive(owner),
                        "repo" to JsonPrimitive(repo),
                        "pull_number" to JsonPrimitive(prNumber),
                        "commit_id" to JsonPrimitive(commitId),
                        "body" to JsonPrimitive(reviewBody),
                        "event" to JsonPrimitive(result.overallScore),
                        "comments" to JsonArray(inlineComments)
                    )
                )
            } else {
                // Только общий комментарий
                postReviewComment(owner, repo, prNumber, reviewBody)
            }
            true
        } catch (e: Exception) {
            println("Ошибка публикации ревью: ${e.message}")
            // Fallback - публикуем как обычный комментарий
            postReviewComment(owner, repo, prNumber, reviewBody)
        }
    }

    // === Private methods ===

    private suspend fun getPrInfo(owner: String, repo: String, prNumber: Int): String {
        val result = githubClient?.callTool(
            "get_pull_request",
            mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "pull_number" to JsonPrimitive(prNumber)
            )
        )
        return result?.content?.firstOrNull()?.text ?: ""
    }

    private suspend fun getPrDiff(owner: String, repo: String, prNumber: Int): String {
        val result = githubClient?.callTool(
            "get_pull_request_diff",
            mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "pull_number" to JsonPrimitive(prNumber)
            )
        )
        return result?.content?.firstOrNull()?.text ?: ""
    }

    private suspend fun getPrFiles(owner: String, repo: String, prNumber: Int): List<String> {
        val result = githubClient?.callTool(
            "get_pull_request_files",
            mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "pull_number" to JsonPrimitive(prNumber)
            )
        )
        val content = result?.content?.firstOrNull()?.text ?: return emptyList()

        // Парсим список файлов из ответа
        return try {
            val files = json.parseToJsonElement(content).jsonArray
            files.mapNotNull { it.jsonObject["filename"]?.jsonPrimitive?.content }
        } catch (e: Exception) {
            // Если не JSON, пробуем построчный парсинг
            content.lines().filter { it.isNotBlank() }
        }
    }

    private suspend fun getLatestCommitId(owner: String, repo: String, prNumber: Int): String? {
        val result = githubClient?.callTool(
            "list_pull_request_commits",
            mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "pull_number" to JsonPrimitive(prNumber)
            )
        )
        val content = result?.content?.firstOrNull()?.text ?: return null

        return try {
            val commits = json.parseToJsonElement(content).jsonArray
            commits.lastOrNull()?.jsonObject?.get("sha")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun buildRagContext(prInfo: String, changedFiles: List<String>): String {
        if (ragService == null) return ""

        val contextParts = mutableListOf<String>()

        // Ищем релевантную документацию по названию PR
        val prTitle = extractPrTitle(prInfo)
        if (prTitle.isNotBlank()) {
            val ragResult = ragService.search(prTitle, topK = 3, minSimilarity = 0.3f)
            if (ragResult.results.isNotEmpty()) {
                contextParts.add(ragResult.formattedContext)
            }
        }

        // Ищем контекст для изменённых файлов
        for (file in changedFiles.take(5)) {
            val fileContext = ragService.search(file, topK = 2, minSimilarity = 0.4f)
            if (fileContext.results.isNotEmpty()) {
                contextParts.add("Контекст для $file:\n${fileContext.formattedContext}")
            }
        }

        return if (contextParts.isNotEmpty()) {
            """
            <project_context>
            ${contextParts.joinToString("\n\n")}
            </project_context>
            """.trimIndent()
        } else ""
    }

    private fun extractPrTitle(prInfo: String): String {
        return try {
            val prJson = json.parseToJsonElement(prInfo).jsonObject
            prJson["title"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            // Пробуем найти title в тексте
            val titleMatch = Regex("\"title\":\\s*\"([^\"]+)\"").find(prInfo)
            titleMatch?.groupValues?.getOrNull(1) ?: ""
        }
    }

    private fun buildReviewPrompt(
        prInfo: String,
        diff: String,
        changedFiles: List<String>,
        ragContext: String
    ): String {
        return buildString {
            appendLine("## Pull Request для ревью")
            appendLine()

            appendLine("### Информация о PR")
            appendLine("```json")
            appendLine(prInfo.take(2000)) // Ограничиваем размер
            appendLine("```")
            appendLine()

            appendLine("### Изменённые файлы (${changedFiles.size})")
            changedFiles.forEach { appendLine("- $it") }
            appendLine()

            if (ragContext.isNotBlank()) {
                appendLine("### Контекст проекта (из RAG)")
                appendLine(ragContext)
                appendLine()
            }

            appendLine("### Diff изменений")
            appendLine("```diff")
            appendLine(diff.take(15000)) // Ограничиваем размер diff
            appendLine("```")
            appendLine()

            appendLine("Проведи тщательное code review этого PR. Укажи все найденные проблемы.")
        }
    }

    private suspend fun getReviewFromLlm(prompt: String): String {
        val request = LlmRequest(
            model = llmClient.model,
            messages = listOf(
                LlmMessage(role = ChatRole.USER, content = prompt)
            ),
            systemPrompt = SYSTEM_PROMPT_CODE_REVIEW,
            temperature = 0.3,
            maxTokens = 4096
        )

        val response = llmClient.send(request)
        return response.text
    }

    private fun parseReviewResponse(
        response: String,
        prNumber: Int,
        prInfo: String
    ): ReviewResult {
        val comments = mutableListOf<ReviewComment>()
        var summary = ""
        var overallScore = "COMMENT"

        // Парсим структурированный ответ
        val lines = response.lines()
        var currentSection = ""
        val sectionContent = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("## Summary") || line.startsWith("## Итог") -> {
                    if (currentSection == "comments") {
                        parseCommentsSection(sectionContent.toString(), comments)
                    }
                    currentSection = "summary"
                    sectionContent.clear()
                }
                line.startsWith("## Comments") || line.startsWith("## Замечания") -> {
                    if (currentSection == "summary") {
                        summary = sectionContent.toString().trim()
                    }
                    currentSection = "comments"
                    sectionContent.clear()
                }
                line.startsWith("## Score") || line.startsWith("## Оценка") -> {
                    if (currentSection == "comments") {
                        parseCommentsSection(sectionContent.toString(), comments)
                    }
                    currentSection = "score"
                    sectionContent.clear()
                }
                else -> {
                    sectionContent.appendLine(line)
                }
            }
        }

        // Обрабатываем последнюю секцию
        when (currentSection) {
            "summary" -> summary = sectionContent.toString().trim()
            "comments" -> parseCommentsSection(sectionContent.toString(), comments)
            "score" -> {
                val scoreText = sectionContent.toString().uppercase()
                overallScore = when {
                    "APPROVE" in scoreText -> "APPROVE"
                    "REQUEST_CHANGES" in scoreText || "ЗАПРОС" in scoreText -> "REQUEST_CHANGES"
                    else -> "COMMENT"
                }
            }
        }

        // Если summary пустой, берём первые строки ответа
        if (summary.isBlank()) {
            summary = response.take(500)
        }

        return ReviewResult(
            prNumber = prNumber,
            prTitle = extractPrTitle(prInfo),
            summary = summary,
            comments = comments,
            overallScore = overallScore,
            stats = ReviewStats(
                filesChanged = 0, // Заполняется из prInfo
                additions = 0,
                deletions = 0,
                commentsGenerated = comments.size
            )
        )
    }

    private fun parseCommentsSection(content: String, comments: MutableList<ReviewComment>) {
        // Парсим комментарии в формате:
        // - **file.kt:123** [warning]: Сообщение
        val commentPattern = Regex(
            """\*\*([^:*]+):?(\d+)?\*\*\s*\[(\w+)]:\s*(.+)""",
            RegexOption.MULTILINE
        )

        commentPattern.findAll(content).forEach { match ->
            val (file, lineStr, severity, message) = match.destructured
            comments.add(
                ReviewComment(
                    file = file.trim(),
                    line = lineStr.toIntOrNull(),
                    severity = severity.lowercase(),
                    message = message.trim()
                )
            )
        }

        // Альтернативный формат: ### file.kt
        val altPattern = Regex("""###\s+([^\n]+)\n([\s\S]*?)(?=###|$)""")
        altPattern.findAll(content).forEach { match ->
            val file = match.groupValues[1].trim()
            val fileComments = match.groupValues[2]

            // Ищем строки с замечаниями
            val linePattern = Regex("""[Ll]ine\s*(\d+):\s*\[?(\w+)]?\s*(.+)""")
            linePattern.findAll(fileComments).forEach { lineMatch ->
                comments.add(
                    ReviewComment(
                        file = file,
                        line = lineMatch.groupValues[1].toIntOrNull(),
                        severity = lineMatch.groupValues[2].ifBlank { "suggestion" }.lowercase(),
                        message = lineMatch.groupValues[3].trim()
                    )
                )
            }
        }
    }

    private fun formatReviewBody(result: ReviewResult): String {
        return buildString {
            appendLine("## AI Code Review")
            appendLine()
            appendLine("### Summary")
            appendLine(result.summary)
            appendLine()

            if (result.comments.isNotEmpty()) {
                appendLine("### Issues Found (${result.comments.size})")
                appendLine()

                val grouped = result.comments.groupBy { it.severity }

                grouped["critical"]?.let { critical ->
                    appendLine("**Critical Issues:**")
                    critical.forEach { c ->
                        appendLine("- ${c.file}${c.line?.let { ":$it" } ?: ""}: ${c.message}")
                    }
                    appendLine()
                }

                grouped["warning"]?.let { warnings ->
                    appendLine("**Warnings:**")
                    warnings.forEach { c ->
                        appendLine("- ${c.file}${c.line?.let { ":$it" } ?: ""}: ${c.message}")
                    }
                    appendLine()
                }

                grouped["suggestion"]?.let { suggestions ->
                    appendLine("**Suggestions:**")
                    suggestions.forEach { c ->
                        appendLine("- ${c.file}${c.line?.let { ":$it" } ?: ""}: ${c.message}")
                    }
                    appendLine()
                }

                grouped["nitpick"]?.let { nitpicks ->
                    appendLine("**Nitpicks:**")
                    nitpicks.forEach { c ->
                        appendLine("- ${c.file}${c.line?.let { ":$it" } ?: ""}: ${c.message}")
                    }
                    appendLine()
                }
            }

            appendLine("---")
            appendLine("*Generated by AI PR Reviewer with RAG context*")
        }
    }

    private fun formatInlineComment(comment: ReviewComment): String {
        val severityEmoji = when (comment.severity) {
            "critical" -> "[CRITICAL]"
            "warning" -> "[WARNING]"
            "suggestion" -> "[SUGGESTION]"
            "nitpick" -> "[NITPICK]"
            else -> ""
        }
        return "$severityEmoji ${comment.message}"
    }

    companion object {
        /**
         * Парсинг URL PR в компоненты (owner, repo, prNumber)
         */
        fun parsePrUrl(url: String): Triple<String, String, Int>? {
            // Формат: https://github.com/owner/repo/pull/123
            val pattern = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
            val match = pattern.find(url) ?: return null
            return Triple(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3].toInt()
            )
        }
    }
}
