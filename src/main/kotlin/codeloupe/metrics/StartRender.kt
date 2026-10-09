package codeloupe.metrics

import codeloupe.platform.Tenths

/** The starting-context lines of `codeloupe metrics` and `metrics compare`, worded as the workspace script words them. */
internal object StartRender {
    private const val MIN_SHARE_PCT = 0.5

    fun line(start: StartBlock): String =
        "   start: S med ${MetricsRender.fmt(start.s)} = " +
            start.tokens.entries.filter { it.value > 0 }.sortedByDescending { it.value }.joinToString("  ") { (k, v) -> "$k ${MetricsRender.fmt(v)}" } +
            "  | ${percent(start.startOfRolePct)}% of the role's cost, ${percent(start.startPct ?: 0.0)}% of all"

    /** Nothing when either report has no starting contexts for the role. */
    fun compare(before: StartBlock?, after: StartBlock?): List<String> {
        if (before == null || after == null) return emptyList()
        val change = if (before.s != 0L) " (${if (after.s >= before.s) "+" else ""}${Math.round(100.0 * (after.s - before.s) / before.s)}%)" else ""
        val head = "   start S ${MetricsRender.fmt(before.s)}→${MetricsRender.fmt(after.s)}$change  " +
            "share of role cost ${delta(before.startOfRolePct, after.startOfRolePct)}  of all ${delta(before.startPct ?: 0.0, after.startPct ?: 0.0)}"
        val names = (before.sharePct.keys + after.sharePct.keys).distinct()
            .filter { (before.sharePct[it] ?: 0.0) >= MIN_SHARE_PCT || (after.sharePct[it] ?: 0.0) >= MIN_SHARE_PCT }
        return listOf(head, "   share of S by source: " + names.joinToString("  ") { "$it ${delta(before.sharePct[it] ?: 0.0, after.sharePct[it] ?: 0.0)}" })
    }

    private fun delta(x: Double, y: Double) = "${percent(x)}%→${percent(y)}% (${if (y >= x) "+" else ""}${percent(Tenths.of(y - x))}%)"

    private fun percent(x: Double) = MetricsRender.percent(x)
}
