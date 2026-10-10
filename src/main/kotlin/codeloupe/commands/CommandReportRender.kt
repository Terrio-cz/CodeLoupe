package codeloupe.commands

import java.util.Locale

/** The text of `codeloupe metrics commands`: a header line, the table of groups, then the same by tool category, role and week. */
object CommandReportRender {
    private const val CATEGORIES = 8
    private const val SLICE_ROWS = 5

    fun text(report: CommandReport): String {
        val lines = ArrayList<String>()
        val period = "${report.since?.take(10) ?: "the start"} .. ${report.until?.take(10) ?: "now"}" + (report.role?.let { ", role $it" } ?: "")
        lines += "commands $period: ${report.runs} runs, ${count(report.calls)} calls, ${tokens(report.resultTokens)} result tokens, ${tokens(report.argTokens)} argument tokens"
        lines += "results carried ${report.costPct} % of the cost of ${tokens(report.cost)} weighted tokens" + (report.money?.let { ", " + money(it, report.currency) } ?: "") +
            if (report.unpricedRuns > 0) " (${report.unpricedRuns} runs of an unpriced model left out of the money)" else ""
        if (report.groups.isEmpty()) return lines.joinToString("\n") + "\nno tool calls in this period"
        lines += ""
        lines += "top ${report.groups.size} of ${report.groupCount} groups:"
        lines += table(report.groups, report.money != null, withRuns = true)
        lines += ""
        lines += "by tool category (the categories of metrics collect):"
        lines += report.categories.take(CATEGORIES).map { "  ${it.category.padEnd(18)} ${count(it.calls).padStart(7)} calls  ${tokens(it.resultTokens).padStart(7)} result tokens  ${"${it.costPct} %".padStart(7)} of cost" }
        slices("by role", report.roles, lines, report.money != null)
        slices("by week", report.weeks, lines, report.money != null)
        return lines.joinToString("\n")
    }

    private fun slices(title: String, slices: List<CommandSlice>, lines: MutableList<String>, priced: Boolean) {
        if (slices.size < 2) return
        lines += ""
        lines += "$title:"
        for (s in slices) {
            lines += "${s.key}  ${s.runs} runs, ${count(s.calls)} calls, ${tokens(s.resultTokens)} result tokens, results carried ${s.costPct} % of ${tokens(s.cost)}"
            lines += table(s.groups.take(SLICE_ROWS), priced, withRuns = false).map { "  $it" }
        }
    }

    private fun table(groups: List<CommandGroup>, priced: Boolean, withRuns: Boolean): List<String> {
        val width = (groups.maxOfOrNull { it.command.length } ?: 0).coerceIn(7, 60)
        val head = "command".padEnd(width) + "  " + listOf("calls", "runs", "results", "args", "repeats", "errors", "cost %").joinToString(" ") { it.padStart(7) } + if (priced) "     money" else ""
        return listOf(head) + groups.map { g ->
            val money = if (priced && g.money != null) String.format(Locale.ROOT, "%10.2f", g.money) else ""
            val runs = if (withRuns && g.topRuns.isNotEmpty()) "  runs " + g.topRuns.joinToString(", ") { it.id } else ""
            g.command.take(width).padEnd(width) + "  " + listOf(count(g.calls), count(g.runs), tokens(g.resultTokens), tokens(g.argTokens), count(g.repeats), count(g.errors), "${g.costPct}")
                .joinToString(" ") { it.padStart(7) } + money + runs
        }
    }

    private fun count(n: Int) = n.toString()

    private fun tokens(n: Long): String = when {
        n >= 10_000_000 -> String.format(Locale.ROOT, "%.0fM", n / 1_000_000.0)
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.ROOT, "%.0fk", n / 1_000.0)
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    private fun money(amount: Double, currency: String) = String.format(Locale.ROOT, "%.2f %s", amount, currency)
}
