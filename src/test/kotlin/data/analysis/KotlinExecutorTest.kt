package org.example.data.analysis

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinExecutorTest {

    private val executor = KotlinExecutor()

    @Test
    fun `executes simple expression`() {
        val result = executor.execute("2 + 2", AnalysisContext("", "", createEmptySchema()))

        assertIs<ExecutionResult.Success>(result)
        assertEquals(4, (result as ExecutionResult.Success).output)
    }

    @Test
    fun `executes code with data variable`() {
        val context = AnalysisContext(
            filePath = "test.csv",
            fileContent = "a,b\n1,2\n3,4",
            schema = createEmptySchema()
        )

        val code = """
            data.lines().size
        """.trimIndent()

        val result = executor.execute(code, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(3, (result as ExecutionResult.Success).output)
    }

    @Test
    fun `returns error for invalid code`() {
        val result = executor.execute("invalid syntax {{{", createEmptyContext())

        assertIs<ExecutionResult.Error>(result)
        assertTrue((result as ExecutionResult.Error).message.isNotEmpty())
    }

    @Test
    fun `executes aggregation on CSV data`() {
        val context = AnalysisContext(
            filePath = "sales.csv",
            fileContent = "product,amount\napple,10\nbanana,20\napple,15",
            schema = createEmptySchema()
        )

        val code = """
            val lines = data.lines().drop(1)
            lines.map { it.split(",")[1].toInt() }.sum()
        """.trimIndent()

        val result = executor.execute(code, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals(45, (result as ExecutionResult.Success).output)
    }

    @Test
    fun `measures execution time`() {
        val result = executor.execute("Thread.sleep(50); 1", createEmptyContext())

        assertIs<ExecutionResult.Success>(result)
        assertTrue((result as ExecutionResult.Success).executionTimeMs >= 50)
    }

    private fun createEmptySchema() = FileSchema(
        type = FileType.UNKNOWN,
        filePath = "",
        sampleData = "",
        totalLines = 0,
        fileSizeBytes = 0
    )

    private fun createEmptyContext() = AnalysisContext("", "", createEmptySchema())
}
