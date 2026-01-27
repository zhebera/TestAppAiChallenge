package org.example.app.commands

import org.example.voice.VoiceInputService

/**
 * Command /voice - voice input using microphone.
 *
 * Records audio until 3 seconds of silence, transcribes with Whisper,
 * and sends the recognized text to LLM.
 *
 * Prerequisites:
 * - sox: brew install sox
 * - whisper: pipx install openai-whisper
 */
class VoiceCommand(
    private val voiceService: VoiceInputService = VoiceInputService()
) : Command {

    override fun matches(input: String): Boolean {
        return input.trim().lowercase().startsWith("/voice")
    }

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        // Check dependencies first
        val status = voiceService.checkDependencies()
        if (!status.allAvailable) {
            println(status.getErrorMessage())
            println()
            return CommandResult.Continue
        }

        println("🎤 Слушаю... (${voiceService.silenceTimeout.toInt()} сек тишины = стоп)")

        val result = voiceService.listen(
            onRecordingStart = { print("   [запись...]") },
            onRecordingStop = { print("\r                    \r") },
            onTranscribing = { print("   [распознавание...]") }
        )

        print("\r                         \r")

        return result.fold(
            onSuccess = { text ->
                println("✓ Распознано: \"$text\"")
                println()
                // Return VoiceInput so ChatLoop processes it as user message
                CommandResult.VoiceInput(text)
            },
            onFailure = { error ->
                println("✗ Ошибка: ${error.message}")
                println()
                CommandResult.Continue
            }
        )
    }
}
