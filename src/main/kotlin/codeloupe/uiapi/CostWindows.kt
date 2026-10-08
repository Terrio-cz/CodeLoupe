package codeloupe.uiapi

import codeloupe.ingest.RunWriter
import codeloupe.platform.IsoTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Weighted tokens by day and hour from the hourly buckets of the ingest. Days are the daemon's local days; the buckets are
 * whole hours, so in a zone whose offset has minutes a day's edge is off by up to half an hour.
 */
internal class CostWindows(private val hours: Map<Long, Double>, private val zone: ZoneId) {
    /** Tokens used from [from] (inclusive) to [to] (exclusive), instants in epoch ms. */
    fun between(from: Long, to: Long): Long = Math.round(hours.entries.sumOf { (h, c) -> if (h * RunWriter.HOUR_MS in from until to) c else 0.0 })

    fun hourly(count: Int, now: Instant): List<Overview.CostPoint> {
        val last = Math.floorDiv(now.toEpochMilli(), RunWriter.HOUR_MS)
        return (last - count + 1..last).map { h -> Overview.CostPoint(IsoTime.of(Instant.ofEpochMilli(h * RunWriter.HOUR_MS)), Math.round(hours[h] ?: 0.0), 0) }
    }

    fun daily(count: Int, today: LocalDate): List<Overview.CostPoint> = (count - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        Overview.CostPoint(IsoTime.of(Instant.ofEpochMilli(from)), between(from, to), 0)
    }

    companion object {
        /** The first hour bucket that can matter for [days] days back from [now]. */
        fun firstHour(now: Instant, days: Long): Long = Math.floorDiv(now.minusSeconds((days + 1) * 86_400).toEpochMilli(), RunWriter.HOUR_MS)
    }
}
