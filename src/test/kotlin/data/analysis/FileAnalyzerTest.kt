package org.example.data.analysis

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileAnalyzerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var analyzer: FileAnalyzer

    @BeforeEach
    fun setup() {
        analyzer = FileAnalyzer(tempDir)
    }

    @Test
    fun `findFile finds exact match`() {
        val testFile = tempDir.resolve("test.csv").createFile()
        testFile.writeText("a,b,c\n1,2,3")

        val results = analyzer.findFile("test.csv")

        assertEquals(1, results.size)
        assertEquals("test.csv", results[0].fileName.toString())
    }

    @Test
    fun `findFile finds by partial name`() {
        tempDir.resolve("sales_2024.csv").createFile().writeText("data")
        tempDir.resolve("sales_2023.csv").createFile().writeText("data")
        tempDir.resolve("users.csv").createFile().writeText("data")

        val results = analyzer.findFile("sales")

        assertEquals(2, results.size)
        assertTrue(results.all { it.fileName.toString().contains("sales") })
    }

    @Test
    fun `findFile searches in subdirectories`() {
        val subDir = tempDir.resolve("data").toFile().apply { mkdir() }
        Path.of(subDir.path, "nested.log").createFile().writeText("log data")

        val results = analyzer.findFile("nested.log")

        assertEquals(1, results.size)
    }

    @Test
    fun `analyzeStructure detects CSV columns`() {
        val csvFile = tempDir.resolve("data.csv").createFile()
        csvFile.writeText("id,name,amount\n1,Alice,100.50\n2,Bob,200.75")

        val schema = analyzer.analyzeStructure(csvFile)

        assertEquals(FileType.CSV, schema.type)
        assertNotNull(schema.columns)
        assertEquals(3, schema.columns!!.size)
        assertEquals("id", schema.columns!![0].name)
        assertEquals("Int", schema.columns!![0].inferredType)
        assertEquals("name", schema.columns!![1].name)
        assertEquals("String", schema.columns!![1].inferredType)
        assertEquals("amount", schema.columns!![2].name)
        assertEquals("Double", schema.columns!![2].inferredType)
    }

    @Test
    fun `analyzeStructure detects JSON structure`() {
        val jsonFile = tempDir.resolve("data.json").createFile()
        jsonFile.writeText("""[{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]""")

        val schema = analyzer.analyzeStructure(jsonFile)

        assertEquals(FileType.JSON, schema.type)
        assertNotNull(schema.jsonStructure)
        assertTrue(schema.jsonStructure!!.contains("id"))
    }

    @Test
    fun `readSample returns first N lines`() {
        val logFile = tempDir.resolve("app.log").createFile()
        logFile.writeText((1..100).joinToString("\n") { "Line $it" })

        val sample = analyzer.readSample(logFile, lines = 10)

        assertEquals(10, sample.lines().size)
        assertTrue(sample.startsWith("Line 1"))
    }
}
