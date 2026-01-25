package org.example.data.analysis

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DataAnalysisToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var tool: DataAnalysisTool

    @BeforeEach
    fun setup() {
        tool = DataAnalysisTool(tempDir)
    }

    @Test
    fun `analyzeFile returns schema for CSV`() {
        val csvFile = tempDir.resolve("test.csv").createFile()
        csvFile.writeText("id,name,score\n1,Alice,95\n2,Bob,87")

        val result = tool.analyzeFile("test.csv")

        assertContains(result, "CSV")
        assertContains(result, "id")
        assertContains(result, "name")
        assertContains(result, "score")
    }

    @Test
    fun `analyzeFile returns error for missing file`() {
        val result = tool.analyzeFile("nonexistent.csv")

        assertContains(result, "не найден")
    }

    @Test
    fun `executeKotlin runs code and returns result`() {
        val csvFile = tempDir.resolve("data.csv").createFile()
        csvFile.writeText("a,b\n1,2\n3,4")

        // Сначала анализируем файл чтобы установить контекст
        tool.analyzeFile("data.csv")

        val result = tool.executeKotlin("data.lines().size")

        assertContains(result, "3")
    }

    @Test
    fun `executeKotlin returns error without prior analysis`() {
        val result = tool.executeKotlin("2 + 2")

        assertContains(result, "сначала")
    }

    @Test
    fun `formatResult creates table from map`() {
        val data = mapOf("Alice" to 10, "Bob" to 20)
        val result = tool.formatResult(data, "table")

        assertContains(result, "Alice")
        assertContains(result, "10")
        assertContains(result, "Bob")
        assertContains(result, "20")
    }

    @Test
    fun `getToolDefinitions returns all tools`() {
        val tools = tool.getToolDefinitions()

        assertTrue(tools.any { it.name == "analyze_file" })
        assertTrue(tools.any { it.name == "execute_kotlin" })
        assertTrue(tools.any { it.name == "format_result" })
    }
}
