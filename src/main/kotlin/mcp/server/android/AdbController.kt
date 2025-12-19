package org.example.mcp.server.android

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Контроллер для выполнения ADB команд
 */
class AdbController(
    private val adbPath: String = System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" }
        ?: "/Users/andrei/Library/Android/sdk/platform-tools/adb"
) {

    /**
     * Выполняет ADB команду и возвращает результат
     */
    fun executeCommand(vararg args: String, timeoutSeconds: Long = 30): CommandResult {
        val command = listOf(adbPath) + args.toList()
        return runCommand(command, timeoutSeconds)
    }

    /**
     * Выполняет shell команду на устройстве
     */
    fun shell(command: String, timeoutSeconds: Long = 30): CommandResult {
        return executeCommand("shell", command, timeoutSeconds = timeoutSeconds)
    }

    /**
     * Проверяет, подключено ли устройство
     */
    fun isDeviceConnected(): Boolean {
        val result = executeCommand("devices")
        if (!result.success) return false

        val lines = result.output.lines()
        return lines.any { line ->
            line.contains("emulator") && (line.contains("device") || line.contains("online"))
        }
    }

    /**
     * Ждёт подключения устройства
     */
    fun waitForDevice(timeoutSeconds: Long = 120): CommandResult {
        return executeCommand("wait-for-device", timeoutSeconds = timeoutSeconds)
    }

    /**
     * Проверяет, завершилась ли загрузка устройства
     */
    fun isBootCompleted(): Boolean {
        val result = shell("getprop sys.boot_completed")
        return result.success && result.output.trim() == "1"
    }

    /**
     * Ждёт полной загрузки устройства
     */
    fun waitForBoot(timeoutSeconds: Long = 180): CommandResult {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000

        // Сначала ждём подключения устройства
        val waitResult = waitForDevice(timeoutSeconds)
        if (!waitResult.success) {
            return waitResult
        }

        // Затем ждём завершения загрузки
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (isBootCompleted()) {
                // Дополнительная пауза для полной инициализации UI
                Thread.sleep(3000)
                return CommandResult(true, "Device boot completed")
            }
            Thread.sleep(2000)
        }

        return CommandResult(false, "Timeout waiting for boot completion")
    }

    /**
     * Делает скриншот и возвращает base64
     */
    fun takeScreenshot(): CommandResult {
        val tempFile = File.createTempFile("screenshot", ".png")
        try {
            // Делаем скриншот на устройстве
            val screencapResult = shell("screencap -p /sdcard/screenshot_temp.png")
            if (!screencapResult.success) {
                return CommandResult(false, "Failed to capture screenshot: ${screencapResult.output}")
            }

            // Скачиваем файл
            val pullResult = executeCommand("pull", "/sdcard/screenshot_temp.png", tempFile.absolutePath)
            if (!pullResult.success) {
                return CommandResult(false, "Failed to pull screenshot: ${pullResult.output}")
            }

            // Удаляем временный файл на устройстве
            shell("rm /sdcard/screenshot_temp.png")

            // Читаем и конвертируем в base64
            val bytes = tempFile.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)

            return CommandResult(true, base64)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Нажимает на координаты экрана
     */
    fun tap(x: Int, y: Int): CommandResult {
        return shell("input tap $x $y")
    }

    /**
     * Вводит текст
     */
    fun inputText(text: String): CommandResult {
        // Экранируем специальные символы для shell
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")

        return shell("input text \"$escapedText\"")
    }

    /**
     * Выполняет свайп
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): CommandResult {
        return shell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Нажимает кнопку по keycode
     */
    fun pressKey(keycode: Int): CommandResult {
        return shell("input keyevent $keycode")
    }

    /**
     * Нажимает кнопку по имени (HOME, BACK, ENTER и т.д.)
     */
    fun pressKeyByName(keyName: String): CommandResult {
        val keycode = KEYCODES[keyName.uppercase()]
            ?: return CommandResult(false, "Unknown key: $keyName. Available: ${KEYCODES.keys}")
        return pressKey(keycode)
    }

    /**
     * Запускает приложение по имени пакета
     */
    fun launchApp(packageName: String): CommandResult {
        // Сначала проверяем, что пакет установлен
        val packageCheck = shell("pm list packages | grep -x 'package:$packageName'")
        if (!packageCheck.success || packageCheck.output.isBlank()) {
            return CommandResult(false, "Package '$packageName' not installed. Use list_packages to see available apps.")
        }

        // Запускаем через monkey
        val monkeyResult = shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1")

        // Даём время на запуск
        Thread.sleep(500)

        // Проверяем, что приложение действительно на переднем плане
        val focusCheck = shell("dumpsys window | grep mCurrentFocus")
        val launched = focusCheck.output.contains(packageName)

        return if (launched) {
            CommandResult(true, "Launched $packageName")
        } else if (monkeyResult.output.contains("Events injected: 1")) {
            // monkey сработал, но приложение может не иметь launcher activity
            CommandResult(false, "Package exists but has no launcher activity or failed to start")
        } else {
            CommandResult(false, "Failed to launch: ${monkeyResult.output}")
        }
    }

    /**
     * Получает список установленных пакетов
     */
    fun listPackages(filter: String? = null): CommandResult {
        val command = if (filter != null) {
            "pm list packages | grep -i $filter"
        } else {
            "pm list packages"
        }
        val result = shell(command)
        if (!result.success) return result

        val packages = result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .sorted()

        return CommandResult(true, packages.joinToString("\n"))
    }

    /**
     * Получает информацию об экране
     */
    fun getScreenInfo(): CommandResult {
        val sizeResult = shell("wm size")
        val densityResult = shell("wm density")

        if (!sizeResult.success) return sizeResult

        val size = sizeResult.output.trim()
        val density = if (densityResult.success) densityResult.output.trim() else "unknown"

        return CommandResult(true, "$size\n$density")
    }

    /**
     * Останавливает приложение
     */
    fun forceStopApp(packageName: String): CommandResult {
        return shell("am force-stop $packageName")
    }

    /**
     * Получает текущую активность
     */
    fun getCurrentActivity(): CommandResult {
        val result = shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
        return if (result.success && result.output.isNotBlank()) {
            // Извлекаем имя компонента из вывода
            val match = Regex("[a-zA-Z][a-zA-Z0-9_.]*\\/[a-zA-Z][a-zA-Z0-9_.]*").find(result.output)
            CommandResult(true, match?.value ?: result.output.trim())
        } else {
            // Альтернативный способ
            val altResult = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            if (altResult.success && altResult.output.isNotBlank()) {
                val match = Regex("[a-zA-Z][a-zA-Z0-9_.]*\\/[a-zA-Z][a-zA-Z0-9_.]*").find(altResult.output)
                CommandResult(true, match?.value ?: altResult.output.trim())
            } else {
                CommandResult(false, "Could not determine current activity")
            }
        }
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return CommandResult(false, "Command timed out after ${timeoutSeconds}s")
            }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            CommandResult(exitCode == 0, output.trim())
        } catch (e: Exception) {
            CommandResult(false, "Error executing command: ${e.message}")
        }
    }

    companion object {
        val KEYCODES = mapOf(
            "HOME" to 3,
            "BACK" to 4,
            "CALL" to 5,
            "ENDCALL" to 6,
            "DPAD_UP" to 19,
            "DPAD_DOWN" to 20,
            "DPAD_LEFT" to 21,
            "DPAD_RIGHT" to 22,
            "DPAD_CENTER" to 23,
            "VOLUME_UP" to 24,
            "VOLUME_DOWN" to 25,
            "POWER" to 26,
            "CAMERA" to 27,
            "CLEAR" to 28,
            "ENTER" to 66,
            "DEL" to 67,
            "DELETE" to 67,
            "BACKSPACE" to 67,
            "TAB" to 61,
            "SPACE" to 62,
            "MENU" to 82,
            "SEARCH" to 84,
            "MEDIA_PLAY_PAUSE" to 85,
            "MEDIA_STOP" to 86,
            "MEDIA_NEXT" to 87,
            "MEDIA_PREVIOUS" to 88,
            "APP_SWITCH" to 187,
            "RECENT_APPS" to 187,
            "SCREENSHOT" to 120
        )
    }
}

data class CommandResult(
    val success: Boolean,
    val output: String
)
