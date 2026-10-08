package codeloupe.metrics

import java.util.Locale

/** The text of `metrics collect` money columns, `metrics what-if` and the money line of `metrics compare`. */
object MoneyRender {
    const val CAVEAT = "what-if keeps each run's token counts: an upper bound on a saving or a cost, since another model needs other turns " +
        "(a cheaper one often more) and may be worse at the task; cache hit rates are assumed the same."

    fun money(table: PriceTable, amount: Double): String = String.format(Locale.ROOT, "%.2f", amount) + " " + table.currency

    /** One line per role: money next to the weighted units, with the runs it could not price. */
    fun roleLines(runs: List<RunSummary>, prices: PriceTable): List<String> {
        val byRole = MetricsMoney.byRole(runs, prices)
        val lines = byRole.entries.sortedByDescending { it.value.priced }.map { (role, m) ->
            "$role  ${money(prices, m.priced)}" + if (m.unpricedRuns > 0) "  (+${m.unpricedRuns} of ${m.runs} runs unpriced)" else ""
        }
        return listOf("money in ${prices.currency} at the prices of ${prices.asOf} (config.json metrics.prices overrides them):") + lines.map { "  $it" } +
            unpriced(byRole.values.flatMap { it.unpricedModels }.toSet())
    }

    /** Role x model table of what the runs would have cost on each of [targets]. */
    fun whatIf(runs: List<RunSummary>, prices: PriceTable, roles: List<String>, targets: List<String>): String {
        val width = (listOf("role") + roles).maxOf { it.length }
        val head = "role".padEnd(width) + "  runs  now" + targets.joinToString("") { "  $it" }
        val lines = ArrayList<String>()
        lines += "what-if in ${prices.currency} at the prices of ${prices.asOf}:"
        lines += head
        for (role in roles) {
            val own = runs.count { it.role == role && prices.priceOf(it.model) != null }
            val now = MetricsMoney.actual(runs, prices, role)
            lines += role.padEnd(width) + "  ${own.toString().padStart(4)}  ${money(prices, now)}" + targets.joinToString("") { target ->
                val cost = MetricsMoney.asIf(runs, prices, role, prices.models.getValue(target))
                "  " + (money(prices, cost) + change(now, cost)).padEnd(target.length)
            }
        }
        lines += CAVEAT
        lines += unpriced(MetricsMoney.byRole(runs, prices).values.flatMap { it.unpricedModels }.toSet())
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** The money change of each role between two reports. */
    fun compare(before: List<RunSummary>, after: List<RunSummary>, prices: PriceTable): List<String> {
        val a = MetricsMoney.byRole(before, prices)
        val b = MetricsMoney.byRole(after, prices)
        val lines = b.keys.filter { it in a }.map { role -> "  $role  ${money(prices, a.getValue(role).priced)} -> ${money(prices, b.getValue(role).priced)}" +
            change(a.getValue(role).priced, b.getValue(role).priced) }
        return if (lines.isEmpty()) emptyList() else listOf("money in ${prices.currency} at the prices of ${prices.asOf}:") + lines
    }

    private fun change(before: Double, after: Double): String =
        if (before <= 0.0) "" else " (${if (after >= before) "+" else ""}${Math.round(100.0 * (after - before) / before)}%)"

    private fun unpriced(models: Set<String>): List<String> =
        if (models.isEmpty()) emptyList() else listOf("unpriced models, left out: ${models.sorted().joinToString(", ")} (add them under config.json metrics.prices.models)")
}
