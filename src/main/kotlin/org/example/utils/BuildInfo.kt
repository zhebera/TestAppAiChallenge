package org.example.utils

import java.time.Instant

object BuildInfo {
    val BUILD_TIME: String = Instant.now().toString()
}