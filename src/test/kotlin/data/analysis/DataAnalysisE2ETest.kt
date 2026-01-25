package org.example.data.analysis

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * End-to-end тест полного цикла анализа данных
 */
class DataAnalysisE2ETest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: DataAnalysisService

    @BeforeEach
    fun setup() {
        service = DataAnalysisService(tempDir)

        // Создаём тестовые файлы
        tempDir.resolve("sales.csv").createFile().writeText("""
            product,quantity,price
            apple,10,1.50
            banana,20,0.75
            apple,15,1.50
            orange,8,2.00
            banana,5,0.75
        """.trimIndent())

        tempDir.resolve("errors.log").createFile().writeText("""
            2024-01-15 10:23:45 ERROR NullPointerException at Service.kt:42
            2024-01-15 10:24:12 WARN Slow query detected
            2024-01-15 10:25:00 ERROR TimeoutException at Client.kt:100
            2024-01-15 10:26:33 ERROR NullPointerException at Handler.kt:55
            2024-01-15 10:27:00 INFO Application started
            2024-01-15 10:28:15 ERROR NullPointerException at Service.kt:42
        """.trimIndent())
    }

    @Test
    fun `analyzes CSV and calculates top product`() {
        // 1. Анализируем файл
        val schemaResult = service.handleToolCall("analyze_file", mapOf("path" to "sales.csv"))
        assertContains(schemaResult, "product")
        assertContains(schemaResult, "quantity")

        // 2. Выполняем код для подсчёта
        val codeResult = service.handleToolCall("execute_kotlin", mapOf(
            "code" to """
                data.lines().drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split(",") }
                    .groupBy { it[0] }
                    .mapValues { entry -> entry.value.sumOf { it[1].toInt() } }
                    .maxByOrNull { it.value }
            """.trimIndent()
        ))

        // apple: 10+15=25, banana: 20+5=25, orange: 8
        assertContains(codeResult, "25")
    }

    @Test
    fun `analyzes logs and counts errors`() {
        // 1. Анализируем лог
        val schemaResult = service.handleToolCall("analyze_file", mapOf("path" to "errors.log"))
        assertContains(schemaResult, "LOG")

        // 2. Считаем ошибки по типу
        val codeResult = service.handleToolCall("execute_kotlin", mapOf(
            "code" to """
                data.lines()
                    .filter { "ERROR" in it }
                    .map { line ->
                        val match = Regex("ERROR (\\w+)").find(line)
                        match?.groupValues?.get(1) ?: "Unknown"
                    }
                    .groupingBy { it }
                    .eachCount()
            """.trimIndent()
        ))

        // NullPointerException: 3, TimeoutException: 1
        assertContains(codeResult, "NullPointerException")
        assertContains(codeResult, "3")
    }

    @Test
    fun `detects analysis queries correctly`() {
        assertTrue(service.isAnalysisQuery("проанализируй sales.csv"))
        assertTrue(service.isAnalysisQuery("какая ошибка чаще в errors.log"))
        assertTrue(service.isAnalysisQuery("покажи статистику продаж"))
    }
}
