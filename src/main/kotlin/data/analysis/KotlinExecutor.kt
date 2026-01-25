package org.example.data.analysis

import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * Выполняет сгенерированный Kotlin-код для анализа данных
 */
class KotlinExecutor {

    private val engineManager = ScriptEngineManager()

    /**
     * Выполнить Kotlin-код с доступом к данным файла
     *
     * @param code Kotlin-код для выполнения
     * @param context Контекст с данными файла
     * @return Результат выполнения
     */
    fun execute(code: String, context: AnalysisContext): ExecutionResult {
        val engine = engineManager.getEngineByExtension("kts")
            ?: return ExecutionResult.Error("Kotlin scripting engine not available")

        val startTime = System.currentTimeMillis()

        return try {
            // Добавляем переменные в контекст скрипта через bindings
            // Используем уникальные имена в bindings чтобы избежать конфликтов
            engine.put("_data_", context.fileContent)
            engine.put("_filePath_", context.filePath)
            engine.put("_schema_", context.schema)

            // Оборачиваем код для доступа к переменным
            val wrappedCode = """
                val data = bindings["_data_"] as String
                val filePath = bindings["_filePath_"] as String
                $code
            """.trimIndent()

            val result = engine.eval(wrappedCode)
            val executionTime = System.currentTimeMillis() - startTime

            ExecutionResult.Success(
                output = result ?: "null",
                executionTimeMs = executionTime
            )
        } catch (e: ScriptException) {
            ExecutionResult.Error(
                message = e.message ?: "Script execution error",
                stackTrace = e.stackTraceToString()
            )
        } catch (e: Exception) {
            ExecutionResult.Error(
                message = e.message ?: "Unknown error",
                stackTrace = e.stackTraceToString()
            )
        }
    }
}
