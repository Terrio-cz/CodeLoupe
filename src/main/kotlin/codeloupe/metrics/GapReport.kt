package codeloupe.metrics

import kotlinx.serialization.Serializable

/** The gaps of a period by week, tool and query shape, most frequent first within a week; [calls] counts every CodeLoupe call seen. */
@Serializable
data class GapReport(val runs: Int, val calls: Int, val rows: List<GapRow>) {
    fun render(): String {
        if (rows.isEmpty()) return "no gaps in $calls CodeLoupe calls of $runs runs"
        val lines = ArrayList<String>()
        lines += "$calls CodeLoupe calls in $runs runs, ${rows.sumOf { it.count }} gaps"
        for ((week, inWeek) in rows.groupBy { it.week }.toSortedMap()) {
            lines += week
            inWeek.forEach { lines += "  ${it.count}×  ${it.kind}  ${it.shape}" + if (it.examples.isEmpty()) "" else "  (${it.examples.joinToString(", ")})" }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val EXAMPLES = 3

        fun of(runs: Sequence<Run>): GapReport {
            var count = 0
            var calls = 0
            val gaps = runs.flatMap { run ->
                count++
                calls += run.tools.count { it.category == "codeloupe" }
                GapDetector.detect(run)
            }.toList()
            val rows = gaps.groupBy { listOf(it.week, it.tool, it.shape, it.kind) }.map { (key, same) ->
                GapRow(key[0], key[1], key[2], key[3], same.size, same.mapNotNull { it.token }.distinct().take(EXAMPLES))
            }.sortedWith(compareBy<GapRow> { it.week }.thenByDescending { it.count }.thenBy { it.shape })
            return GapReport(count, calls, rows)
        }
    }
}
