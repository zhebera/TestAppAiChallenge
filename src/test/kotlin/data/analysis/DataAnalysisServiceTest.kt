package org.example.data.analysis

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DataAnalysisServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: DataAnalysisService

    @BeforeEach
    fun setup() {
        service = DataAnalysisService(tempDir)
    }

    @Test
    fun `handleToolCall executes analyze_file`() {
        val csvFile = tempDir.resolve("test.csv").createFile()
        csvFile.writeText("id,name\n1,Alice")

        val result = service.handleToolCall("analyze_file", mapOf("path" to "test.csv"))

        assertContains(result, "CSV")
        assertContains(result, "id")
    }

    @Test
    fun `handleToolCall executes execute_kotlin after analyze`() {
        val csvFile = tempDir.resolve("data.csv").createFile()
        csvFile.writeText("a,b\n1,2\n3,4")

        service.handleToolCall("analyze_file", mapOf("path" to "data.csv"))
        val result = service.handleToolCall("execute_kotlin", mapOf("code" to "data.lines().size"))

        assertContains(result, "3")
    }

    @Test
    fun `handleToolCall returns error for unknown tool`() {
        val result = service.handleToolCall("unknown_tool", emptyMap())

        assertContains(result, "Неизвестный")
    }

    @Test
    fun `isAnalysisQuery detects file mentions`() {
        assertTrue(service.isAnalysisQuery("проанализируй sales.csv"))
        assertTrue(service.isAnalysisQuery("покажи статистику из logs.json"))
        assertTrue(service.isAnalysisQuery("какие ошибки в app.log"))
    }

    @Test
    fun `getSystemPrompt returns analysis prompt`() {
        val prompt = service.getSystemPrompt()

        assertContains(prompt, "analyze_file")
        assertContains(prompt, "execute_kotlin")
    }
}
