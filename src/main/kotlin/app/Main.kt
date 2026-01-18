package app

import app.commands.*
import app.config.AppConfig
import app.rag.RagService
import app.utils.Logger
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Logger.info("Запуск AI Assistant...")
    
    val config = AppConfig.load()
    val ragService = RagService(config)
    
    val commands = mapOf(
        "help" to HelpCommand(),
        "config" to ConfigCommand(config),
        "rag" to RagCommand(ragService),
        "exit" to ExitCommand(),
        "quit" to ExitCommand()
    )
    
    // Автоматическая индексация проекта при старте
    Logger.info("Выполняется автоматическая индексация проекта...")
    try {
        ragService.indexProject()
        Logger.info("Автоматическая индексация завершена успешно")
    } catch (e: Exception) {
        Logger.error("Ошибка при автоматической индексации: ${e.message}")
    }
    
    Logger.info("AI Assistant готов к работе!")
    Logger.info("Введите /help для получения справки")
    
    while (true) {
        print("\n> ")
        val input = readlnOrNull()?.trim() ?: continue
        
        if (input.isEmpty()) continue
        
        val parts = input.split(" ", limit = 2)
        val commandName = parts[0].removePrefix("/")
        val args = if (parts.size > 1) parts[1] else ""
        
        val command = commands[commandName]
        if (command != null) {
            try {
                command.execute(args)
            } catch (e: Exception) {
                Logger.error("Ошибка выполнения команды: ${e.message}")
            }
        } else {
            // Обычный запрос к AI
            try {
                val response = ragService.query(input)
                println("\nОтвет: $response")
            } catch (e: Exception) {
                Logger.error("Ошибка при обработке запроса: ${e.message}")
            }
        }
    }
}