package codeloupe.tracker

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Short UTC times for answers (`2026-10-07 10:12Z`) and parsing of `since`. */
object Times {
    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC)

    fun short(ms: Long?): String = ms?.let { minute.format(Instant.ofEpochMilli(it)) } ?: "never"

    /** `2026-10-07T10:12:00Z`, `2026-10-07 10:12Z`, `2026-10-07T10:12` (UTC) or `2026-10-07`; null when not a time. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(' ', 'T')
        val candidates = listOf(t, "${t.removeSuffix("Z")}:00Z", "${t}Z", "${t}T00:00:00Z")
        return candidates.firstNotNullOfOrNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
