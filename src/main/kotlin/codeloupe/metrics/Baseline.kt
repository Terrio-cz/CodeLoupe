package codeloupe.metrics

/**
 * What the daemon takes from a baseline report (`codeloupe metrics collect --label baseline`): the mean cost of one run
 * of each role, in weighted tokens. The mean, not the median, because savings are compared as totals and a median of
 * the skewed run costs would sit below the total of the same runs.
 */
class Baseline(val label: String, val since: String?, val until: String?, val generated: String, val runs: Int, private val meanCost: Map<String, Double>) {
    fun meanCost(role: String): Double? = meanCost[role]

    val roles: Int get() = meanCost.size

    companion object {
        fun of(report: MetricsReport): Baseline {
            val means = report.aggregate.mapNotNull { (role, a) -> if (a.runs > 0 && a.cost.sum > 0) role to a.cost.sum.toDouble() / a.runs else null }.toMap()
            return Baseline(report.label, report.since, report.until, report.generated, report.aggregate.values.sumOf { it.runs }, means)
        }
    }
}
