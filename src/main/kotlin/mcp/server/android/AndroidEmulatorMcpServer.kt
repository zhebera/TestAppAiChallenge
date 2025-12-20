package org.example.mcp.server.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * MCP сервер для управления Android эмулятором
 *
 * Инструменты:
 * - start_emulator: запуск эмулятора
 * - stop_emulator: остановка эмулятора
 * - wait_for_boot: ожидание загрузки
 * - launch_app: запуск приложения
 * - take_screenshot: скриншот экрана
 * - tap: нажатие на координаты
 * - input_text: ввод текста
 * - swipe: свайп
 * - press_key: нажатие кнопки
 * - list_packages: список приложений
 * - get_emulator_info: информация об эмуляторе
 */
class AndroidEmulatorMcpServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val emulatorController = EmulatorController()
    private val adbController = AdbController()

    // Лог-файл для отладки
    private val logFile = File(System.getProperty("user.home"), "android-mcp-debug.log")

    private fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
    }

    fun run() {
        log("=== MCP Server started ===")
        log("Working dir: ${System.getProperty("user.dir")}")
        log("ANDROID_HOME: ${System.getenv("ANDROID_HOME") ?: "not set"}")

        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue

            log(">>> Received: ${line.take(200)}...")

            try {
                val request = json.parseToJsonElement(line).jsonObject
                val method = request["method"]?.jsonPrimitive?.content
                log("Processing method: $method")

                val response = handleRequest(request)
                if (response.isNotEmpty()) {
                    val responseStr = json.encodeToString(response)
                    log("<<< Sending: ${responseStr.take(200)}...")
                    println(responseStr)
                    System.out.flush()
                    log("Response sent and flushed")
                }
            } catch (e: Exception) {
                log("!!! Exception: ${e.message}")
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    }
                }
                println(json.encodeToString(errorResponse))
                System.out.flush()
            }
        }
        log("=== MCP Server stopped ===")
    }

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> JsonObject(emptyMap())
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "android-emulator-mcp")
                    put("version", "1.0.0")
                }
            }
        }
    }

    private fun handleToolsList(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("tools") {
                    // start_emulator
                    addJsonObject {
                        put("name", "start_emulator")
                        put("description", "Запуск Android эмулятора")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("avd_name") {
                                    put("type", "string")
                                    put("description", "Имя AVD (опционально)")
                                }
                            }
                        }
                    }

                    // stop_emulator
                    addJsonObject {
                        put("name", "stop_emulator")
                        put("description", "Остановка эмулятора")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // wait_for_boot
                    addJsonObject {
                        put("name", "wait_for_boot")
                        put("description", "Ожидание загрузки эмулятора")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("timeout_seconds") {
                                    put("type", "integer")
                                    put("default", 180)
                                }
                            }
                        }
                    }

                    // launch_app
                    addJsonObject {
                        put("name", "launch_app")
                        put("description", "Запуск приложения по package name")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("package_name") {
                                    put("type", "string")
                                }
                            }
                            putJsonArray("required") { add("package_name") }
                        }
                    }

                    // take_screenshot
                    addJsonObject {
                        put("name", "take_screenshot")
                        put("description", "Скриншот экрана (сохраняется в файл ~/android-screenshots/)")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // tap
                    addJsonObject {
                        put("name", "tap")
                        put("description", "Нажатие на координаты экрана")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("x") { put("type", "integer") }
                                putJsonObject("y") { put("type", "integer") }
                            }
                            putJsonArray("required") { add("x"); add("y") }
                        }
                    }

                    // input_text
                    addJsonObject {
                        put("name", "input_text")
                        put("description", "Ввод текста")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("text") { put("type", "string") }
                            }
                            putJsonArray("required") { add("text") }
                        }
                    }

                    // swipe
                    addJsonObject {
                        put("name", "swipe")
                        put("description", "Свайп от (x1,y1) к (x2,y2)")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("x1") { put("type", "integer") }
                                putJsonObject("y1") { put("type", "integer") }
                                putJsonObject("x2") { put("type", "integer") }
                                putJsonObject("y2") { put("type", "integer") }
                                putJsonObject("duration_ms") { put("type", "integer"); put("default", 300) }
                            }
                            putJsonArray("required") { add("x1"); add("y1"); add("x2"); add("y2") }
                        }
                    }

                    // press_key
                    addJsonObject {
                        put("name", "press_key")
                        put("description", "Нажатие кнопки: HOME, BACK, ENTER, DELETE, MENU, VOLUME_UP/DOWN")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("key") { put("type", "string") }
                            }
                            putJsonArray("required") { add("key") }
                        }
                    }

                    // list_packages
                    addJsonObject {
                        put("name", "list_packages")
                        put("description", "Список приложений")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("filter") { put("type", "string") }
                            }
                        }
                    }

                    // get_emulator_info
                    addJsonObject {
                        put("name", "get_emulator_info")
                        put("description", "Информация об эмуляторе")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // get_screen_info
                    addJsonObject {
                        put("name", "get_screen_info")
                        put("description", "Размер экрана")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // list_avds
                    addJsonObject {
                        put("name", "list_avds")
                        put("description", "Список доступных AVD")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }

                    // force_stop_app
                    addJsonObject {
                        put("name", "force_stop_app")
                        put("description", "Остановка приложения")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("package_name") { put("type", "string") }
                            }
                            putJsonArray("required") { add("package_name") }
                        }
                    }

                    // get_current_activity
                    addJsonObject {
                        put("name", "get_current_activity")
                        put("description", "Текущая Activity")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }
                }
            }
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val argumentsElement = params?.get("arguments")
        val arguments = if (argumentsElement is JsonObject) argumentsElement else JsonObject(emptyMap())

        val result = when (toolName) {
            "start_emulator" -> startEmulator(arguments)
            "stop_emulator" -> stopEmulator()
            "wait_for_boot" -> waitForBoot(arguments)
            "launch_app" -> launchApp(arguments)
            "take_screenshot" -> takeScreenshot()
            "tap" -> tap(arguments)
            "input_text" -> inputText(arguments)
            "swipe" -> swipe(arguments)
            "press_key" -> pressKey(arguments)
            "list_packages" -> listPackages(arguments)
            "get_emulator_info" -> getEmulatorInfo()
            "get_screen_info" -> getScreenInfo()
            "list_avds" -> listAvds()
            "force_stop_app" -> forceStopApp(arguments)
            "get_current_activity" -> getCurrentActivity()
            else -> "Неизвестный инструмент: $toolName"
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            }
        }
    }

    // === Tool implementations ===

    private fun startEmulator(arguments: JsonObject): String {
        val avdName = arguments["avd_name"]?.jsonPrimitive?.content
        val headless = arguments["headless"]?.jsonPrimitive?.booleanOrNull ?: false

        val result = emulatorController.startEmulator(avdName, headless)

        return if (result.success) {
            "✓ Эмулятор запускается. Используй wait_for_boot для ожидания загрузки."
        } else {
            "✗ ${result.message}"
        }
    }

    private fun stopEmulator(): String {
        val result = emulatorController.stopEmulator()
        return if (result.success) {
            "✓ ${result.message}"
        } else {
            "✗ Ошибка: ${result.message}"
        }
    }

    private fun waitForBoot(arguments: JsonObject): String {
        val timeout = arguments["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 180

        val result = emulatorController.waitForBoot(timeout.toLong())

        return if (result.success) {
            "✓ Эмулятор загружен и готов к работе"
        } else {
            "✗ Ошибка: ${result.message}"
        }
    }

    private fun launchApp(arguments: JsonObject): String {
        val packageName = arguments["package_name"]?.jsonPrimitive?.content
            ?: return "✗ Ошибка: параметр 'package_name' обязателен"

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен. Сначала используй start_emulator."
        }

        val result = adbController.launchApp(packageName)

        return if (result.success) {
            "✓ Приложение $packageName запущено"
        } else {
            "✗ Ошибка запуска: ${result.output}"
        }
    }

    private fun takeScreenshot(): String {
        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Эмулятор не запущен"
        }

        val result = adbController.takeScreenshotToFile()

        return if (result.success) {
            "✓ Скриншот: ${result.output}"
        } else {
            "✗ ${result.output}"
        }
    }

    private fun tap(arguments: JsonObject): String {
        val x = arguments["x"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'x' обязателен"
        val y = arguments["y"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'y' обязателен"

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.tap(x, y)

        return if (result.success) {
            "✓ Нажатие на координаты ($x, $y) выполнено"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }

    private fun inputText(arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return "✗ Ошибка: параметр 'text' обязателен"

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.inputText(text)

        return if (result.success) {
            "✓ Текст \"$text\" введён"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }

    private fun swipe(arguments: JsonObject): String {
        val x1 = arguments["x1"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'x1' обязателен"
        val y1 = arguments["y1"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'y1' обязателен"
        val x2 = arguments["x2"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'x2' обязателен"
        val y2 = arguments["y2"]?.jsonPrimitive?.intOrNull
            ?: return "✗ Ошибка: параметр 'y2' обязателен"
        val durationMs = arguments["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.swipe(x1, y1, x2, y2, durationMs)

        return if (result.success) {
            "✓ Свайп от ($x1, $y1) до ($x2, $y2) выполнен"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }

    private fun pressKey(arguments: JsonObject): String {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return "✗ Ошибка: параметр 'key' обязателен"

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.pressKeyByName(key)

        return if (result.success) {
            "✓ Кнопка $key нажата"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }

    private fun listPackages(arguments: JsonObject): String {
        val filter = arguments["filter"]?.jsonPrimitive?.content

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Эмулятор не запущен"
        }

        val result = adbController.listPackages(filter)

        return if (result.success) {
            val packages = result.output.lines().filter { it.isNotBlank() }
            if (packages.isEmpty()) {
                "Пакеты не найдены${if (filter != null) " (фильтр: $filter)" else ""}"
            } else {
                // Компактный вывод: максимум 15 пакетов
                val maxShow = 15
                val shown = packages.take(maxShow)
                val remaining = packages.size - maxShow
                shown.joinToString(", ") + if (remaining > 0) " (+$remaining)" else ""
            }
        } else {
            "✗ ${result.output}"
        }
    }

    private fun getEmulatorInfo(): String {
        val result = emulatorController.getEmulatorInfo()
        return if (result.success) {
            result.message
        } else {
            "✗ Ошибка: ${result.message}"
        }
    }

    private fun getScreenInfo(): String {
        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Эмулятор не запущен"
        }

        val result = adbController.getScreenInfo()
        return if (result.success) {
            result.output.replace("\n", ", ")
        } else {
            "✗ ${result.output}"
        }
    }

    private fun listAvds(): String {
        val avds = emulatorController.listAvds()
        return if (avds.isNotEmpty()) {
            "AVD: ${avds.joinToString(", ")}"
        } else {
            "AVD не найдены"
        }
    }

    private fun forceStopApp(arguments: JsonObject): String {
        val packageName = arguments["package_name"]?.jsonPrimitive?.content
            ?: return "✗ Ошибка: параметр 'package_name' обязателен"

        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.forceStopApp(packageName)

        return if (result.success) {
            "✓ Приложение $packageName остановлено"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }

    private fun getCurrentActivity(): String {
        if (!emulatorController.isEmulatorRunning()) {
            return "✗ Ошибка: Эмулятор не запущен."
        }

        val result = adbController.getCurrentActivity()

        return if (result.success) {
            "Текущая Activity: ${result.output}"
        } else {
            "✗ Ошибка: ${result.output}"
        }
    }
}

fun main() {
    AndroidEmulatorMcpServer().run()
}