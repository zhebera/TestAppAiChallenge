package org.example.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for voice input using sox (recording) and Whisper (transcription).
 *
 * Prerequisites:
 * - sox: brew install sox
 * - whisper: pipx install openai-whisper
 */
class VoiceInputService(
    val silenceTimeout: Double = 3.0,
    private val silenceThreshold: String = "3%",
    private val whisperModel: String = "base",
    private val language: String = "ru"
) {
    private val tempDir = File(System.getProperty("java.io.tmpdir"))

    /**
     * Check if sox and whisper are available.
     */
    fun checkDependencies(): DependencyStatus {
        val soxAvailable = runCommand("which", "rec") != null
        val whisperAvailable = runCommand("which", "whisper") != null
        return DependencyStatus(soxAvailable, whisperAvailable)
    }

    /**
     * Record audio from microphone using sox with Voice Activity Detection.
     * Recording stops after [silenceTimeout] seconds of silence.
     */
    suspend fun record(): Result<File> = withContext(Dispatchers.IO) {
        val audioFile = File.createTempFile("voice_input_", ".wav", tempDir)

        try {
            val process = ProcessBuilder(
                "rec",
                "-r", "16000",      // 16kHz sample rate (optimal for Whisper)
                "-c", "1",          // Mono
                audioFile.absolutePath,
                "silence",
                "1", "0.1", silenceThreshold,  // Start recording when sound detected
                "1", silenceTimeout.toString(), silenceThreshold  // Stop after silence
            )
                .redirectErrorStream(true)
                .start()

            // Capture output for better error messages
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 && audioFile.exists() && audioFile.length() > 0) {
                Result.success(audioFile)
            } else {
                audioFile.delete()
                val errorDetail = if (output.isNotBlank()) ": $output" else ""
                Result.failure(Exception("Recording failed (exit code: $exitCode)$errorDetail"))
            }
        } catch (e: Exception) {
            audioFile.delete()
            Result.failure(Exception("Recording error: ${e.message}"))
        }
    }

    /**
     * Transcribe audio file using Whisper CLI.
     */
    suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val outputDir = audioFile.parentFile

            val process = ProcessBuilder(
                "whisper",
                audioFile.absolutePath,
                "--model", whisperModel,
                "--language", language,
                "--output_format", "txt",
                "--output_dir", outputDir.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            // Capture output for debugging
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // Whisper creates .txt file with same name as input
            val txtFile = File(outputDir, audioFile.nameWithoutExtension + ".txt")

            if (exitCode == 0 && txtFile.exists()) {
                val text = txtFile.readText().trim()
                // Cleanup
                txtFile.delete()
                audioFile.delete()

                if (text.isNotEmpty()) {
                    Result.success(text)
                } else {
                    Result.failure(Exception("No speech detected"))
                }
            } else {
                audioFile.delete()
                if (txtFile.exists()) txtFile.delete()
                Result.failure(Exception("Transcription failed: $output"))
            }
        } catch (e: Exception) {
            audioFile.delete()
            Result.failure(Exception("Transcription error: ${e.message}"))
        }
    }

    /**
     * Combined method: record audio and transcribe it.
     * Callbacks allow UI feedback during the process.
     */
    suspend fun listen(
        onRecordingStart: () -> Unit = {},
        onRecordingStop: () -> Unit = {},
        onTranscribing: () -> Unit = {}
    ): Result<String> {
        onRecordingStart()

        val recordResult = record()
        onRecordingStop()

        return recordResult.fold(
            onSuccess = { audioFile ->
                onTranscribing()
                transcribe(audioFile)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    private fun runCommand(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output.trim() else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Status of required dependencies (sox, whisper).
 */
data class DependencyStatus(
    val soxAvailable: Boolean,
    val whisperAvailable: Boolean
) {
    val allAvailable: Boolean get() = soxAvailable && whisperAvailable

    fun getErrorMessage(): String? = when {
        !soxAvailable && !whisperAvailable ->
            "Sox and Whisper not found. Install: brew install sox && pipx install openai-whisper"
        !soxAvailable -> "Sox not found. Install: brew install sox"
        !whisperAvailable -> "Whisper not found. Install: pipx install openai-whisper"
        else -> null
    }
}
