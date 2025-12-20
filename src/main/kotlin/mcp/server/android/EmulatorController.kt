package org.example.mcp.server.android

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Контроллер для управления Android эмулятором
 */
class EmulatorController(
    private val sdkPath: String = System.getenv("ANDROID_HOME")
        ?: "/Users/andrei/Library/Android/sdk",
    private val adbController: AdbController = AdbController()
) {
    private val emulatorPath = "$sdkPath/emulator/emulator"
    private val avdManagerPath = "$sdkPath/cmdline-tools/latest/bin/avdmanager"

    private var emulatorProcess: Process? = null

    /**
     * Получает список доступных AVD
     */
    fun listAvds(): List<String> {
        val avdDir = File(System.getProperty("user.home"), ".android/avd")
        if (!avdDir.exists()) return emptyList()

        return avdDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".avd") }
            ?.map { it.name.removeSuffix(".avd") }
            ?: emptyList()
    }

    /**
     * Проверяет, запущен ли эмулятор
     */
    fun isEmulatorRunning(): Boolean {
        return adbController.isDeviceConnected()
    }

    /**
     * Запускает эмулятор
     * @param avdName Имя AVD для запуска. Если null, использует первый доступный
     * @param headless Запуск без GUI (для CI/CD)
     */
    fun startEmulator(avdName: String? = null, headless: Boolean = false): EmulatorResult {
        if (isEmulatorRunning()) {
            return EmulatorResult(true, "Emulator is already running")
        }

        val avd = avdName ?: listAvds().firstOrNull()
            ?: return EmulatorResult(false, "No AVD found. Please create an AVD first.")

        val command = mutableListOf(emulatorPath, "-avd", avd)

        if (headless) {
            command.addAll(listOf("-no-window", "-no-audio", "-no-boot-anim"))
        }

        // Добавляем параметры для ускорения запуска
        command.addAll(listOf(
            "-gpu", "auto",
            "-no-snapshot-load"  // Свежий запуск без загрузки снапшота
        ))

        return try {
            val processBuilder = ProcessBuilder(command)
                // Важно: перенаправляем вывод в DEVNULL чтобы не блокироваться
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)

            // Устанавливаем переменные окружения
            processBuilder.environment()["ANDROID_HOME"] = sdkPath
            processBuilder.environment()["ANDROID_SDK_ROOT"] = sdkPath

            emulatorProcess = processBuilder.start()

            // Даём время на запуск процесса
            Thread.sleep(3000)

            // Проверяем, что процесс запустился
            if (emulatorProcess?.isAlive == true) {
                EmulatorResult(true, "Emulator starting with AVD: $avd. Use wait_for_boot to wait for full boot.")
            } else {
                EmulatorResult(false, "Emulator process exited unexpectedly. Check if AVD '$avd' exists and is valid.")
            }
        } catch (e: Exception) {
            EmulatorResult(false, "Failed to start emulator: ${e.message}")
        }
    }

    /**
     * Останавливает эмулятор
     */
    fun stopEmulator(): EmulatorResult {
        if (!isEmulatorRunning()) {
            return EmulatorResult(true, "Emulator is not running")
        }

        // Пробуем корректно завершить через adb emu kill
        try {
            adbController.executeCommand("emu", "kill", timeoutSeconds = 10)
        } catch (_: Exception) {
            // Игнорируем ошибки, попробуем другие способы
        }

        // Даём время на завершение
        Thread.sleep(2000)

        // Если процесс ещё жив, убиваем принудительно
        emulatorProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        emulatorProcess = null

        // Проверяем результат
        if (!isEmulatorRunning()) {
            return EmulatorResult(true, "Emulator stopped successfully")
        }

        // Пробуем через pkill как последнее средство
        try {
            val pkillProcess = ProcessBuilder("pkill", "-f", "qemu-system")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            pkillProcess.waitFor(5, TimeUnit.SECONDS)
            Thread.sleep(1000)
        } catch (_: Exception) {}

        return if (!isEmulatorRunning()) {
            EmulatorResult(true, "Emulator stopped (force killed)")
        } else {
            EmulatorResult(false, "Failed to stop emulator")
        }
    }

    /**
     * Ждёт полной загрузки эмулятора
     */
    fun waitForBoot(timeoutSeconds: Long = 180): EmulatorResult {
        val result = adbController.waitForBoot(timeoutSeconds)
        return EmulatorResult(result.success, result.output)
    }

    /**
     * Перезапускает эмулятор
     */
    fun restartEmulator(avdName: String? = null): EmulatorResult {
        val stopResult = stopEmulator()
        if (!stopResult.success) {
            return EmulatorResult(false, "Failed to stop emulator: ${stopResult.message}")
        }

        Thread.sleep(2000)

        return startEmulator(avdName)
    }

    /**
     * Получает информацию о текущем эмуляторе (компактный вывод)
     */
    fun getEmulatorInfo(): EmulatorResult {
        if (!isEmulatorRunning()) {
            return EmulatorResult(false, "Emulator is not running")
        }

        val propsResult = adbController.shell("getprop ro.build.version.sdk")
        val modelResult = adbController.shell("getprop ro.product.model")

        val info = "Model: ${modelResult.output.trim()}, API: ${propsResult.output.trim()}"

        return EmulatorResult(true, info)
    }
}

data class EmulatorResult(
    val success: Boolean,
    val message: String
)