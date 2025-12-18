package org.example.mcp.server.wikipedia

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API клиент для работы с Wikipedia
 */
class WikipediaApi {

    companion object {
        private const val BASE_URL = "https://ru.wikipedia.org/w/api.php"
        private const val EN_BASE_URL = "https://en.wikipedia.org/w/api.php"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Поиск статей в Wikipedia по запросу
     */
    fun searchArticles(query: String, limit: Int = 5, language: String = "ru"): WikiSearchResult {
        val baseUrl = if (language == "en") EN_BASE_URL else BASE_URL
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = "$baseUrl?action=query&list=search&srsearch=$encodedQuery&srlimit=$limit&format=json&utf8=1"

        return try {
            val response = httpGet(url)
            val jsonResponse = json.parseToJsonElement(response).jsonObject

            val searchResults = jsonResponse["query"]?.jsonObject?.get("search")?.jsonArray ?: JsonArray(emptyList())

            val articles = searchResults.map { item ->
                val obj = item.jsonObject
                WikiArticlePreview(
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    snippet = obj["snippet"]?.jsonPrimitive?.content?.replace(Regex("<[^>]*>"), "") ?: "",
                    pageId = obj["pageid"]?.jsonPrimitive?.int ?: 0
                )
            }

            WikiSearchResult(
                success = true,
                query = query,
                articles = articles,
                totalHits = jsonResponse["query"]?.jsonObject?.get("searchinfo")?.jsonObject?.get("totalhits")?.jsonPrimitive?.int ?: articles.size
            )
        } catch (e: Exception) {
            WikiSearchResult(
                success = false,
                query = query,
                articles = emptyList(),
                error = e.message
            )
        }
    }

    /**
     * Получение полного текста статьи по названию
     */
    fun getArticle(title: String, language: String = "ru"): WikiArticle {
        val baseUrl = if (language == "en") EN_BASE_URL else BASE_URL
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        // Получаем extract (краткое содержание) и полный текст
        val url = "$baseUrl?action=query&titles=$encodedTitle&prop=extracts|info&exintro=false&explaintext=true&inprop=url&format=json&utf8=1"

        return try {
            val response = httpGet(url)
            val jsonResponse = json.parseToJsonElement(response).jsonObject

            val pages = jsonResponse["query"]?.jsonObject?.get("pages")?.jsonObject
            val page = pages?.entries?.firstOrNull()?.value?.jsonObject

            if (page == null || page["pageid"] == null) {
                return WikiArticle(
                    success = false,
                    title = title,
                    content = "",
                    error = "Статья не найдена"
                )
            }

            WikiArticle(
                success = true,
                title = page["title"]?.jsonPrimitive?.content ?: title,
                content = page["extract"]?.jsonPrimitive?.content ?: "",
                url = page["fullurl"]?.jsonPrimitive?.content,
                pageId = page["pageid"]?.jsonPrimitive?.int ?: 0
            )
        } catch (e: Exception) {
            WikiArticle(
                success = false,
                title = title,
                content = "",
                error = e.message
            )
        }
    }

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "KotlinMCPClient/1.0 (https://github.com/example)")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

@Serializable
data class WikiSearchResult(
    val success: Boolean,
    val query: String,
    val articles: List<WikiArticlePreview>,
    val totalHits: Int = 0,
    val error: String? = null
)

@Serializable
data class WikiArticlePreview(
    val title: String,
    val snippet: String,
    val pageId: Int
)

@Serializable
data class WikiArticle(
    val success: Boolean,
    val title: String,
    val content: String,
    val url: String? = null,
    val pageId: Int = 0,
    val error: String? = null
)