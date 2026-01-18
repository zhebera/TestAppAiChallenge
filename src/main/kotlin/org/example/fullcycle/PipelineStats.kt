package org.example.fullcycle

import java.time.LocalDateTime
import java.time.Duration
import kotlin.math.roundToInt

data class PipelineStats(
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val totalDuration: Duration,
    val statesVisited: List<String>,
    val stateTransitions: Int,
    val reviewIterations: Int,
    val ciRetries: Int,
    val successfulMerge: Boolean,
    val finalState: String
) {
    fun getDurationInSeconds(): Long = totalDuration.seconds

    fun getDurationInMinutes(): Double = totalDuration.seconds / 60.0

    fun getAverageIterationTime(): Duration {
        return if (reviewIterations > 0) {
            Duration.ofMillis(totalDuration.toMillis() / reviewIterations)
        } else {
            Duration.ZERO
        }
    }

    fun getStateTransitionRate(): Double {
        val durationInSeconds = getDurationInSeconds()
        return if (durationInSeconds > 0) {
            (stateTransitions.toDouble() / durationInSeconds * 60).roundToInt().toDouble()
        } else {
            0.0
        }
    }

    fun getSuccessRate(): Double {
        return if (successfulMerge) 100.0 else 0.0
    }

    fun getUniqueStatesCount(): Int = statesVisited.distinct().size

    fun getSummary(): String {
        return """
            Pipeline Execution Statistics
            ==============================
            Start Time: $startTime
            End Time: $endTime
            Total Duration: ${getDurationInMinutes().roundToInt()} minutes
            States Visited: ${getUniqueStatesCount()} unique states
            State Transitions: $stateTransitions
            Review Iterations: $reviewIterations
            CI Retries: $ciRetries
            Final State: $finalState
            Successful Merge: $successfulMerge
            Average Iteration Time: ${getAverageIterationTime().seconds} seconds
            State Transition Rate: ${getStateTransitionRate().roundToInt()} transitions/minute
        """.trimIndent()
    }
}