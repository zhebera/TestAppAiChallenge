package org.example.data.persistence

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Таблица для хранения футбольных новостей.
 * Используется для дедупликации и агрегации.
 */
object FootballNewsTable : Table("football_news") {
    val id = varchar("id", 64)  // SHA-256 hash от title+source
    val title = varchar("title", 500)
    val description = text("description")
    val sources = varchar("source", 100)  // ESPN, BBC Sport, Sky Sports
    val link = varchar("link", 500)
    val leagues = varchar("leagues", 200)  // Список лиг через запятую
    val publishedAt = long("published_at")  // Unix timestamp в миллисекундах
    val fetchedAt = long("fetched_at")  // Когда была получена новость
    val isProcessed = bool("is_processed").default(false)  // Включена ли в summary

    override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица для хранения сводок новостей.
 */
object FootballSummaryTable : Table("football_summaries") {
    val id = long("id").autoIncrement()
    val content = text("content")
    val newsIds = text("news_ids")  // ID новостей, включенных в сводку (через запятую)
    val newsCount = integer("news_count")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Утилиты для работы с таблицами футбольных новостей.
 * Таблицы создаются в основной БД через DatabaseConfig.
 */
object FootballDatabaseUtils {

    /**
     * Очистить все данные футбольных новостей.
     * Вызывается при запуске приложения.
     */
    fun clearAllData() {
        transaction {
            FootballNewsTable.deleteAll()
            FootballSummaryTable.deleteAll()
        }
    }
}
