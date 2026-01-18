package org.example.fullcycle

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Serializable
data class TimeoutEvent(
    val id: String,
    val timestamp: String,
    val durationMs: Long,
    val reason: String,
    val status: String
)

class TimeoutHandler(
    private val executor: ScheduledExecutorService,
    private val onTimeoutExpired: (String) -> Unit = {}
) {
    private val activeTimeouts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val timeoutEvents = mutableListOf<TimeoutEvent>()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun setTimeoutMs(id: String, durationMs: Long, reason: String = "MCP operation timeout") {
        cancelTimeout(id)
        
        val future = executor.schedule({
            handleTimeoutExpired(id, reason)
        }, durationMs, TimeUnit.MILLISECONDS)
        
        activeTimeouts[id] = future
        logTimeoutEvent(id, durationMs, reason, "STARTED")
    }

    fun setTimeoutSeconds(id: String, durationSeconds: Long, reason: String = "MCP operation timeout") {
        setTimeoutMs(id, durationSeconds * 1000, reason)
    }

    fun cancelTimeout(id: String): Boolean {
        val future = activeTimeouts.remove(id)
        return if (future != null) {
            future.cancel(false)
            logTimeoutEvent(id, 0, "Timeout cancelled", "CANCELLED")
            true
        } else {
            false
        }
    }

    fun isTimeoutActive(id: String): Boolean {
        val future = activeTimeouts[id]
        return future != null && !future.isDone
    }

    fun getRemainingTimeMs(id: String): Long? {
        val future = activeTimeouts[id] ?: return null
        return if (!future.isDone) {
            future.getDelay(TimeUnit.MILLISECONDS)
        } else {
            null
        }
    }

    fun cancelAllTimeouts() {
        activeTimeouts.forEach { (id, future) ->
            future.cancel(false)
            logTimeoutEvent(id, 0, "All timeouts cancelled", "CANCELLED")
        }
        activeTimeouts.clear()
    }

    fun getTimeoutEvents(): List<TimeoutEvent> = timeoutEvents.toList()

    fun clearTimeoutEvents() {
        timeoutEvents.clear()
    }

    private fun handleTimeoutExpired(id: String, reason: String) {
        logTimeoutEvent(id, 0, reason, "EXPIRED")
        activeTimeouts.remove(id)
        
        try {
            onTimeoutExpired(id)
        } catch (e: Exception) {
            logTimeoutEvent(id, 0, "Error in timeout handler: ${e.message}", "ERROR")
        }
    }

    private fun logTimeoutEvent(id: String, durationMs: Long, reason: String, status: String) {
        val event = TimeoutEvent(
            id = id,
            timestamp = LocalDateTime.now().format(dateFormatter),
            durationMs = durationMs,
            reason = reason,
            status = status
        )
        timeoutEvents.add(event)
        println("[TimeoutHandler] $status - ID: $id, Duration: ${durationMs}ms, Reason: $reason, Time: ${event.timestamp}")
    }

    fun getActiveTimeoutCount(): Int = activeTimeouts.size

    fun getActiveTimeoutIds(): List<String> = activeTimeouts.keys.toList()
}