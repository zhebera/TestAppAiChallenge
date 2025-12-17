package org.example.data.persistence

import org.example.mcp.server.FootballNews
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Репозиторий для работы с футбольными новостями в БД.
 */
class FootballRepository {

    /**
     * Сохранить новость в БД.
     * @return true если новость новая, false если дубликат
     */
    fun saveNews(news: FootballNews): Boolean {
        return transaction {
            val exists = FootballNewsTable.select { FootballNewsTable.id eq news.id }.count() > 0
            if (exists) {
                false
            } else {
                FootballNewsTable.insert {
                    it[id] = news.id
                    it[title] = news.title.take(500)
                    it[description] = news.description
                    it[sources] = news.sources
                    it[link] = news.link
                    it[leagues] = news.leagues.joinToString(",")
                    it[publishedAt] = news.publishedAt.toEpochMilli()
                    it[fetchedAt] = news.fetchedAt.toEpochMilli()
                    it[isProcessed] = false
                }
                true
            }
        }
    }

    /**
     * Сохранить список новостей.
     * @return Количество новых (уникальных) новостей
     */
    fun saveNewsList(newsList: List<FootballNews>): Int {
        var newCount = 0
        for (news in newsList) {
            if (saveNews(news)) {
                newCount++
            }
        }
        return newCount
    }

    /**
     * Получить необработанные новости (для генерации summary).
     */
    fun getUnprocessedNews(): List<FootballNews> {
        return transaction {
            FootballNewsTable.select { FootballNewsTable.isProcessed eq false }
                .orderBy(FootballNewsTable.publishedAt, SortOrder.DESC)
                .map { it.toFootballNews() }
        }
    }

    /**
     * Пометить новости как обработанные.
     */
    fun markAsProcessed(newsIds: List<String>) {
        if (newsIds.isEmpty()) return
        transaction {
            FootballNewsTable.update({ FootballNewsTable.id inList newsIds }) {
                it[isProcessed] = true
            }
        }
    }

    /**
     * Получить последние N новостей.
     */
    fun getLatestNews(limit: Int = 10): List<FootballNews> {
        return transaction {
            FootballNewsTable.selectAll()
                .orderBy(FootballNewsTable.publishedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toFootballNews() }
        }
    }

    /**
     * Получить новости за последние N минут.
     */
    fun getNewsFromLastMinutes(minutes: Int): List<FootballNews> {
        val cutoffTime = Instant.now().minusSeconds(minutes * 60L).toEpochMilli()
        return transaction {
            FootballNewsTable.select { FootballNewsTable.fetchedAt greaterEq cutoffTime }
                .orderBy(FootballNewsTable.publishedAt, SortOrder.DESC)
                .map { it.toFootballNews() }
        }
    }

    /**
     * Сохранить сводку.
     */
    fun saveSummary(content: String, newsIds: List<String>) {
        transaction {
            FootballSummaryTable.insert {
                it[FootballSummaryTable.content] = content
                it[FootballSummaryTable.newsIds] = newsIds.joinToString(",")
                it[newsCount] = newsIds.size
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Получить количество новостей в БД.
     */
    fun getNewsCount(): Long {
        return transaction {
            FootballNewsTable.selectAll().count()
        }
    }

    /**
     * Проверить, существует ли новость с данным ID.
     */
    fun newsExists(newsId: String): Boolean {
        return transaction {
            FootballNewsTable.select { FootballNewsTable.id eq newsId }.count() > 0
        }
    }

    /**
     * Очистить все данные.
     */
    fun clearAll() {
        transaction {
            FootballNewsTable.deleteAll()
            FootballSummaryTable.deleteAll()
        }
    }

    private fun ResultRow.toFootballNews(): FootballNews {
        return FootballNews(
            id = this[FootballNewsTable.id],
            title = this[FootballNewsTable.title],
            description = this[FootballNewsTable.description],
            sources = this[FootballNewsTable.sources],
            link = this[FootballNewsTable.link],
            publishedAt = Instant.ofEpochMilli(this[FootballNewsTable.publishedAt]),
            fetchedAt = Instant.ofEpochMilli(this[FootballNewsTable.fetchedAt]),
            leagues = this[FootballNewsTable.leagues].split(",").filter { it.isNotBlank() }
        )
    }
}
