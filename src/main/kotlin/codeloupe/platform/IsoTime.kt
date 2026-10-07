package codeloupe.platform

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Timestamps as `2026-10-07T08:09:35.123Z`, always with milliseconds. */
object IsoTime {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun now(): String = of(Instant.now())

    fun of(instant: Instant): String = format.format(instant)
}
