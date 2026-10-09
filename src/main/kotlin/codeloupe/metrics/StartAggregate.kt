package codeloupe.metrics

import codeloupe.platform.Tenths
import kotlin.math.floor

/** The [StartBlock] of a role: medians over the runs that have a starting context, with the workspace script's rounding. */
internal object StartAggregate {
    fun of(runs: List<RunSummary>): StartBlock? {
        val started = runs.mapNotNull { r -> r.startCtx?.let { r to it } }
        if (started.isEmpty()) return null
        val contexts = started.map { it.second }
        val tokens = contexts.map(StartSources::tokens)
        val sources = tokens.flatMap { it.keys }.distinct()
        val cost = contexts.sumOf { it.cost }
        val runCost = started.sumOf { it.first.cost }
        return StartBlock(
            runs = started.size,
            s = Pick.of(contexts.map { it.s }).median,
            costSum = cost,
            startOfRolePct = Tenths.of(100.0 * cost / (if (runCost == 0L) 1 else runCost)),
            tokens = sources.associateWith { k -> rounded(median(tokens.map { it[k] ?: 0.0 })) },
            sharePct = sources.associateWith { k -> Tenths.of(100.0 * medianExact(tokens.mapIndexed { i, t -> (t[k] ?: 0.0) / contexts[i].s })) },
        )
    }

    // `Math.round`: halves go up.
    private fun rounded(x: Double): Long = floor(x + 0.5).toLong()

    // The script rounds the middle of an even count; a ratio keeps it exact.
    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size shr 1
        return if (s.size % 2 == 1) s[m] else floor((s[m - 1] + s[m]) / 2.0 + 0.5)
    }

    private fun medianExact(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size shr 1
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
