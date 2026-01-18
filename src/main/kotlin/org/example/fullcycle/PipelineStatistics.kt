package org.example.fullcycle

import java.time.Duration
import java.time.LocalDateTime

data class PipelineStatistics(
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val filesChanged: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val reviewIterations: Int,
    val ciAttempts: Int
) {
    fun getDuration(): Duration = Duration.between(startTime, endTime)
}