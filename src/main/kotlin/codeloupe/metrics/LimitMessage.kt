package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Recognises the limit answers in a transcript line and reads when the limit lifts. */
internal object LimitMessage {
    private const val MAX_CHARS = 300
    private val ANSWER = Regex("^You've hit your (?:(.+?) )?limit", RegexOption.IGNORE_CASE)
    private val RESETS = Regex("""resets\s+(?:([A-Z][a-z]{2})\s+(\d{1,2}),\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val TYPES = mapOf("five_hour" to "session", "seven_day" to "weekly", "seven_day_opus" to "opus", "seven_day_sonnet" to "sonnet")

    /**
     * The limit a transcript [line] reports, or null for any other line. A line counts when Claude Code flagged it `rate_limit`
     * or wrote it as a synthetic assistant message, and its text begins with the answer: a long report that mentions the phrase is none.
     */
    fun of(line: JsonObject, message: JsonObject?, text: String?, atMs: Long): LimitHit? {
        if (text == null || text.length > MAX_CHARS) return null
        val answer = ANSWER.find(text.trim()) ?: return null
        val flagged = line["error"].str() == "rate_limit" || message?.get("model").str() == "<synthetic>"
        if (!flagged) return null
        val quota = line["quotaLimits"].obj()
        val resets = quota?.get("resetsAt")?.let { (it as? JsonPrimitive)?.doubleOrNull }?.let { (it * 1000).toLong() } ?: resetsAt(text, atMs)
        val named = answer.groupValues[1].lowercase(Locale.ROOT).replace("individual ", "")
        val kind = named.ifEmpty { quota?.get("rateLimitType").str()?.let { TYPES[it] } ?: "limit" }
        return LimitHit(atMs, resets, kind, text.trim())
    }

    /** The instant "resets 8pm (Europe/Prague)" or "resets Oct 11, 8pm (Europe/Prague)" names, counted from [fromMs]; null when the text has none. */
    fun resetsAt(text: String, fromMs: Long): Long? {
        val m = RESETS.find(text) ?: return null
        val zone = try { ZoneId.of(m.groupValues[6].trim()) } catch (_: DateTimeException) { return null }
        val hour12 = m.groupValues[3].toInt()
        if (hour12 !in 1..12) return null
        val hour = hour12 % 12 + if (m.groupValues[5].lowercase(Locale.ROOT) == "pm") 12 else 0
        val time = LocalTime.of(hour, m.groupValues[4].ifEmpty { "0" }.toInt().coerceIn(0, 59))
        val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMs), zone)
        val month = m.groupValues[1]
        if (month.isEmpty()) {
            val today = from.toLocalDate().atTime(time).atZone(zone)
            return (if (today.isAfter(from)) today else today.plusDays(1)).toInstant().toEpochMilli()
        }
        val monthOf = Month.entries.firstOrNull { it.name.take(3).equals(month, ignoreCase = true) } ?: return null
        val day = m.groupValues[2].toInt()
        val date = runCatching { LocalDate.of(from.year, monthOf, day) }.getOrNull() ?: return null
        val at = date.atTime(time).atZone(zone)
        return (if (at.isBefore(from.minusDays(1))) at.plusYears(1) else at).toInstant().toEpochMilli()
    }
}
