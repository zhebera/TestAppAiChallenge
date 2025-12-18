package org.example.data.persistence

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/**
 * Конфигурация и инициализация базы данных SQLite.
 */
object DatabaseConfig {
    private const val DB_FOLDER = ".llm-chat"
    private const val DB_NAME = "chat_memory.db"

    private var initialized = false

    /**
     * Инициализирует подключение к SQLite базе данных.
     * Создаёт файл базы в домашней директории пользователя.
     */
    fun init() {
        if (initialized) return

        val dbFolder = File(System.getProperty("user.home"), DB_FOLDER)
        if (!dbFolder.exists()) {
            dbFolder.mkdirs()
        }

        val dbFile = File(dbFolder, DB_NAME)
        val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        Database.connect(
            url = dbUrl,
            driver = "org.sqlite.JDBC"
        )

        // Создаём таблицы если их нет
        transaction {
            SchemaUtils.create(
                MessagesTable,
                CompressedHistoryTable,
                SessionsTable,
            )
        }

        initialized = true
        println("База данных инициализирована: ${dbFile.absolutePath}")
    }

    /**
     * Возвращает путь к файлу базы данных.
     */
    fun getDatabasePath(): String {
        val dbFolder = File(System.getProperty("user.home"), DB_FOLDER)
        val dbFile = File(dbFolder, DB_NAME)
        return dbFile.absolutePath
    }
}
