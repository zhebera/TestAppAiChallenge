package org.example.presentation

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ConsoleInput {

    private val reader: BufferedReader = BufferedReader(
        InputStreamReader(System.`in`, StandardCharsets.UTF_8)
    )

    fun readLine(prompt: String): String? {
        print(prompt)
        return try {
            reader.readLine()
        } catch (t: Throwable) {
            null
        }
    }
}