package org.example.data.analysis

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisModelsTest {

    @Test
    fun `FileType detects CSV by extension`() {
        assertEquals(FileType.CSV, FileType.fromExtension("csv"))
        assertEquals(FileType.CSV, FileType.fromExtension("CSV"))
    }

    @Test
    fun `FileType detects JSON by extension`() {
        assertEquals(FileType.JSON, FileType.fromExtension("json"))
    }

    @Test
    fun `FileType detects LOG by extension`() {
        assertEquals(FileType.LOG, FileType.fromExtension("log"))
    }

    @Test
    fun `FileType returns UNKNOWN for other extensions`() {
        assertEquals(FileType.UNKNOWN, FileType.fromExtension("xyz"))
    }

    @Test
    fun `FileSchema contains required fields`() {
        val schema = FileSchema(
            type = FileType.CSV,
            filePath = "test.csv",
            columns = listOf(ColumnInfo("id", "Int"), ColumnInfo("name", "String")),
            sampleData = "id,name\n1,Alice",
            totalLines = 100,
            fileSizeBytes = 2048
        )

        assertEquals(FileType.CSV, schema.type)
        assertEquals(2, schema.columns?.size)
        assertEquals(100, schema.totalLines)
    }

    @Test
    fun `ExecutionResult Success holds output`() {
        val result = ExecutionResult.Success(output = "42", executionTimeMs = 150)
        assertEquals("42", result.output)
        assertTrue(result.executionTimeMs > 0)
    }

    @Test
    fun `ExecutionResult Error holds message`() {
        val result = ExecutionResult.Error(message = "Division by zero", stackTrace = "at line 5")
        assertEquals("Division by zero", result.message)
    }
}
