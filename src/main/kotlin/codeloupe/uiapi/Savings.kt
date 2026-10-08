package codeloupe.uiapi

import codeloupe.ingest.HourUsage
import codeloupe.metrics.Baseline

/**
 * What the runs of a period would have cost at the baseline. Each finished run whose role the baseline knows counts the
 * baseline's mean cost of one run of that role, spread over the run's hours like its real cost; every other run (a role
 * the baseline lacks, a run still going) counts what it really cost on both sides, so it neither saves nor loses.
 * The saving is therefore a comparison with the average run of the same role before CodeLoupe, not a controlled benchmark.
 */
internal class Savings(rows: List<HourUsage>, baseline: Baseline, activeAfterMs: Long) {
    /** The totals of a window: [actual] cost, what it would cost at the [baseline], and the part of [actual] that was compared. */
    class Window(val actual: Double, val baseline: Double, val coveredActual: Double) {
        val saved: Double get() = baseline - actual

        /** Percent saved on the compared runs; null when none was compared. */
        val savedPct: Double?
            get() = if (coveredActual <= 0.0) null else Math.round(saved / (saved + coveredActual) * PERCENT_TENTHS) / TENTH

        val coveredShare: Double get() = if (actual <= 0.0) 0.0 else coveredActual / actual
    }

    private class Share(val hour: Long, val actual: Double, val baseline: Double, val covered: Boolean)

    private val shares = rows.map { r ->
        val mean = baseline.meanCost(r.role)
        val compared = mean != null && r.runCost > 0 && r.endMs <= activeAfterMs
        Share(r.hour, r.cost, if (compared) r.cost * mean!! / r.runCost else r.cost, compared)
    }

    /** The baseline cost per hour bucket, to draw next to the real one. */
    fun baselineHours(): Map<Long, Double> = shares.groupingBy { it.hour }.fold(0.0) { sum, s -> sum + s.baseline }

    /** The totals of the hour buckets from [fromHour] on. */
    fun from(fromHour: Long): Window = shares.filter { it.hour >= fromHour }.let { part ->
        Window(part.sumOf { it.actual }, part.sumOf { it.baseline }, part.filter { it.covered }.sumOf { it.actual })
    }

    private companion object {
        const val PERCENT_TENTHS = 1000.0
        const val TENTH = 10.0
    }
}
