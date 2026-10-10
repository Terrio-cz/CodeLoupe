package codeloupe.commands

import codeloupe.metrics.MetricsSetup
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** The start of a period as `--since` and the `since` of the UI API give it: `10d`, `24h`, a day (`2026-10-01`) or an ISO instant. */
object CommandPeriod {
    private val RELATIVE = Regex("""^(\d{1,4})([hd])$""")

    fun since(text: String, now: Instant): Instant {
        val relative = RELATIVE.matchEntire(text.trim())
        if (relative != null) {
            val n = relative.groupValues[1].toLong()
            return now.minus(if (relative.groupValues[2] == "h") Duration.ofHours(n) else Duration.ofDays(n))
        }
        return try {
            MetricsSetup.instant(text.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("the period must be like 10d, 24h, 2026-10-01 or an ISO instant, not '$text'")
        }
    }
}
