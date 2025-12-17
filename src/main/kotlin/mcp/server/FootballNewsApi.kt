package org.example.mcp.server

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * API для получения футбольных новостей из RSS фидов.
 * Использует бесплатные источники без API ключей.
 *
 * Поддерживаемые лиги: АПЛ, Ла Лига, РПЛ, Серия А, Бундеслига, Лига 1
 */
class FootballNewsApi {

    companion object {
        // RSS источники футбольных новостей
        private val RSS_FEEDS = listOf(
            "https://www.espn.com/espn/rss/soccer/news" to "ESPN",
            "http://feeds.bbci.co.uk/sport/football/rss.xml" to "BBC Sport",
            "https://www.skysports.com/rss/12040" to "Sky Sports"
        )

        // Ключевые слова для фильтрации по лигам
        private val LEAGUE_KEYWORDS = mapOf(
            "АПЛ" to listOf(
                "premier league", "epl", "english premier",
                "manchester united", "manchester city", "liverpool", "chelsea",
                "arsenal", "tottenham", "newcastle", "aston villa", "west ham",
                "brighton", "everton", "wolves", "crystal palace", "bournemouth",
                "fulham", "nottingham forest", "brentford", "leicester", "ipswich"
            ),
            "Ла Лига" to listOf(
                "la liga", "laliga", "spanish league",
                "real madrid", "barcelona", "atletico madrid", "sevilla",
                "real sociedad", "villarreal", "athletic bilbao", "betis",
                "valencia", "osasuna", "getafe", "celta vigo", "mallorca"
            ),
            "Бундеслига" to listOf(
                "bundesliga", "german league",
                "bayern munich", "borussia dortmund", "bayer leverkusen",
                "rb leipzig", "eintracht frankfurt", "wolfsburg", "freiburg",
                "hoffenheim", "mainz", "union berlin", "werder bremen"
            ),
            "Серия А" to listOf(
                "serie a", "italian league", "calcio",
                "inter milan", "ac milan", "juventus", "napoli", "roma",
                "lazio", "atalanta", "fiorentina", "bologna", "torino",
                "monza", "udinese", "sassuolo", "empoli", "verona"
            ),
            "Лига 1" to listOf(
                "ligue 1", "french league",
                "psg", "paris saint-germain", "marseille", "monaco", "lyon",
                "lille", "lens", "nice", "rennes", "strasbourg", "nantes"
            ),
            "РПЛ" to listOf(
                "russian premier league", "рпл", "russian league",
                "зенит", "zenit", "спартак", "spartak moscow", "цска", "cska",
                "динамо москва", "dynamo moscow", "локомотив", "lokomotiv",
                "краснодар", "krasnodar", "ростов", "rostov"
            )
        )
    }

    /**
     * Получить последние новости из всех RSS источников
     */
    fun fetchLatestNews(): NewsResult {
        val allNews = mutableListOf<FootballNews>()
        val errors = mutableListOf<String>()

        for ((feedUrl, sourceName) in RSS_FEEDS) {
            try {
                val news = fetchFromRss(feedUrl, sourceName)
                allNews.addAll(news)
            } catch (e: Exception) {
                errors.add("$sourceName: ${e.message}")
            }
        }

        return if (allNews.isNotEmpty()) {
            // Фильтруем только релевантные новости о выбранных лигах
            val relevantNews = allNews.filter { news ->
                isRelevantToLeagues(news.title + " " + news.description)
            }
            NewsResult.Success(relevantNews.distinctBy { it.id })
        } else if (errors.isNotEmpty()) {
            NewsResult.Error("Ошибки при получении новостей: ${errors.joinToString("; ")}")
        } else {
            NewsResult.Success(emptyList())
        }
    }

    /**
     * Парсинг RSS фида
     */
    private fun fetchFromRss(feedUrl: String, sourceName: String): List<FootballNews> {
        val url = URL(feedUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FootballNewsBot/1.0)")

        return try {
            val responseText = connection.inputStream.bufferedReader().readText()
            parseRss(responseText, sourceName, feedUrl)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Простой парсер RSS XML
     */
    private fun parseRss(xml: String, sourceName: String, feedUrl: String): List<FootballNews> {
        val news = mutableListOf<FootballNews>()

        // Извлекаем все <item> блоки
        val itemPattern = "<item>(.*?)</item>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val items = itemPattern.findAll(xml)

        for (item in items) {
            val itemContent = item.groupValues[1]

            val title = extractTag(itemContent, "title")?.trim() ?: continue
            val description = extractTag(itemContent, "description")?.trim() ?: ""
            val link = extractTag(itemContent, "link")?.trim() ?: feedUrl
            val pubDate = extractTag(itemContent, "pubDate")

            val publishedAt = parseRssDate(pubDate)
            val newsId = generateNewsId(title, sourceName)

            news.add(
                FootballNews(
                    id = newsId,
                    title = cleanHtml(title),
                    description = cleanHtml(description),
                    sources = sourceName,
                    link = link,
                    publishedAt = publishedAt,
                    fetchedAt = Instant.now(),
                    leagues = detectLeagues(title + " " + description)
                )
            )
        }

        return news
    }

    /**
     * Извлечение значения тега из XML
     */
    private fun extractTag(xml: String, tagName: String): String? {
        // Пробуем CDATA
        val cdataPattern = "<$tagName>\\s*<!\\[CDATA\\[(.+?)\\]\\]>\\s*</$tagName>".toRegex(RegexOption.DOT_MATCHES_ALL)
        cdataPattern.find(xml)?.let { return it.groupValues[1] }

        // Обычный тег
        val pattern = "<$tagName>(.+?)</$tagName>".toRegex(RegexOption.DOT_MATCHES_ALL)
        return pattern.find(xml)?.groupValues?.get(1)
    }

    /**
     * Парсинг даты из RSS формата
     */
    private fun parseRssDate(dateStr: String?): Instant {
        if (dateStr == null) return Instant.now()

        return try {
            // RFC 822 формат: "Tue, 17 Dec 2024 10:30:00 GMT"
            val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
            ZonedDateTime.parse(dateStr, formatter).toInstant()
        } catch (e: Exception) {
            try {
                // Альтернативный формат
                val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                ZonedDateTime.parse(dateStr, formatter).toInstant()
            } catch (e2: Exception) {
                Instant.now()
            }
        }
    }

    /**
     * Генерация уникального ID для новости
     */
    private fun generateNewsId(title: String, source: String): String {
        val content = "$title|$source"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(content.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Очистка HTML тегов из текста
     */
    private fun cleanHtml(text: String): String {
        return text
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * Проверка релевантности новости к выбранным лигам
     */
    private fun isRelevantToLeagues(text: String): Boolean {
        val lowerText = text.lowercase()
        return LEAGUE_KEYWORDS.values.flatten().any { keyword ->
            lowerText.contains(keyword.lowercase())
        }
    }

    /**
     * Определение лиг, к которым относится новость
     */
    private fun detectLeagues(text: String): List<String> {
        val lowerText = text.lowercase()
        return LEAGUE_KEYWORDS.filter { (_, keywords) ->
            keywords.any { keyword -> lowerText.contains(keyword.lowercase()) }
        }.keys.toList()
    }
}

/**
 * Модель футбольной новости
 */
data class FootballNews(
    val id: String,
    val title: String,
    val description: String,
    val sources: String,
    val link: String,
    val publishedAt: Instant,
    val fetchedAt: Instant,
    val leagues: List<String>
)

/**
 * Результат получения новостей
 */
sealed class NewsResult {
    data class Success(val news: List<FootballNews>) : NewsResult()
    data class Error(val message: String) : NewsResult()
}
