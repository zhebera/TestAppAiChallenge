package org.example.presentation

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ConsoleInput : Closeable {

    private val reader: BufferedReader = BufferedReader(
        InputStreamReader(System.`in`, StandardCharsets.UTF_8)
    )

    fun readLine(prompt: String): String? {
        print(prompt)
        System.out.flush()
        return try {
            reader.readLine()
        } catch (t: Throwable) {
            null
        }
    }

    override fun close() {
        try {
            reader.close()
        } catch (_: Throwable) {
            // Ignore close errors
        }
    }
}