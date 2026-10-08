package codeloupe.metrics

import kotlinx.serialization.Serializable

/** The boilerplate share of the new files agents wrote in a period, and the go/no-go it implies for scaffold templates (CL-35). */
@Serializable
data class BoilerplateReport(
    val runs: Int,
    val runCost: Long,
    val total: BoilerplateShare,
    val byKind: Map<String, BoilerplateShare>,
    val byRole: Map<String, BoilerplateShare>,
    val decision: String,
) {
    fun render(): String {
        val lines = ArrayList<String>()
        fun line(name: String, s: BoilerplateShare) =
            "$name  ${s.files} new files, ${MetricsRender.fmt(s.chars)} chars, ${s.sharePct}% skeleton  (write+carry cost ${percent(s.cost)}% of run cost, skeleton part ${percent(s.boilerplateCost)}%)"
        lines += line("all", total)
        byKind.forEach { (k, s) -> lines += "  " + line(k, s) }
        byRole.entries.sortedByDescending { it.value.chars }.take(ROLES_SHOWN).forEach { (r, s) -> lines += "  role " + line(r, s) }
        lines += "decision: $decision"
        return lines.joinToString("\n")
    }

    private fun percent(part: Long) = if (runCost == 0L) "0" else (Math.round(1000.0 * part / runCost) / 10.0).toString()

    companion object {
        private const val ROLES_SHOWN = 6
        const val THRESHOLD_PCT = 30.0

        fun of(runs: Sequence<Run>): BoilerplateReport {
            var count = 0
            var cost = 0L
            val files = runs.flatMap { run ->
                count++
                cost += run.usage.cost()
                BoilerplateAnalyzer.newFiles(run).map { run.role to it }
            }.toList()
            val total = files.fold(BoilerplateShare()) { acc, (_, f) -> acc + f.share }
            val decision = if (total.files == 0) "no data: no new code files in the period"
            else if (total.sharePct >= THRESHOLD_PCT) "go: skeleton is ${total.sharePct}% of new files (threshold ${THRESHOLD_PCT.toInt()}%)"
            else "no-go: skeleton is ${total.sharePct}% of new files (threshold ${THRESHOLD_PCT.toInt()}%)"
            return BoilerplateReport(
                runs = count, runCost = cost, total = total,
                byKind = files.groupBy { it.second.kind }.mapValues { (_, v) -> v.fold(BoilerplateShare()) { a, (_, f) -> a + f.share } },
                byRole = files.groupBy { it.first }.mapValues { (_, v) -> v.fold(BoilerplateShare()) { a, (_, f) -> a + f.share } },
                decision = decision,
            )
        }
    }
}
