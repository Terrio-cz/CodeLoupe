package codeloupe.metrics

import kotlinx.serialization.Serializable

/**
 * The gaps of a period by week, tool and query shape, most frequent first within a week; [calls] counts every CodeLoupe call seen.
 * [settled] counts the empty answers the agent accepted (correct negatives), which are no gaps; [legacy] is set when the report
 * was made with the rule the series began with, where every empty answer is a gap and [settled] is 0.
 * [since] and [generatedAt] say what the report covers and when it was made; the desktop app shows them.
 */
@Serializable
data class GapReport(
    val runs: Int,
    val calls: Int,
    val rows: List<GapRow>,
    val since: String? = null,
    val generatedAt: String? = null,
    val settled: Int = 0,
    val legacy: Boolean = false,
) {
    fun covering(since: String, generatedAt: String) = copy(since = since, generatedAt = generatedAt)

    fun render(): String {
        val negatives = if (legacy) "" else ", $settled settled negatives"
        if (rows.isEmpty()) return "no gaps in $calls CodeLoupe calls of $runs runs$negatives"
        val lines = ArrayList<String>()
        lines += "$calls CodeLoupe calls in $runs runs, ${rows.sumOf { it.count }} gaps$negatives" + if (legacy) " (all empty answers counted)" else ""
        for ((week, inWeek) in rows.groupBy { it.week }.toSortedMap()) {
            lines += week
            inWeek.forEach { lines += "  ${it.count}×  ${it.kind}  ${it.shape}" + if (it.examples.isEmpty()) "" else "  (${it.examples.joinToString(", ")})" }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val EXAMPLES = 3

        fun of(runs: Sequence<Run>, rule: GapRule = GapRule.CURRENT): GapReport {
            var count = 0
            var calls = 0
            var settled = 0
            val gaps = runs.flatMap { run ->
                count++
                calls += run.tools.count { it.category == "codeloupe" }
                if (rule == GapRule.CURRENT) settled += GapDetector.settled(run).size
                GapDetector.detect(run, rule)
            }.toList()
            val rows = gaps.groupBy { listOf(it.week, it.tool, it.shape, it.kind) }.map { (key, same) ->
                GapRow(key[0], key[1], key[2], key[3], same.size, same.mapNotNull { it.token }.distinct().take(EXAMPLES))
            }.sortedWith(compareBy<GapRow> { it.week }.thenByDescending { it.count }.thenBy { it.shape })
            return GapReport(count, calls, rows, settled = settled, legacy = rule == GapRule.LEGACY)
        }
    }
}
