package codeloupe.metrics

import codeloupe.platform.Tenths

/** The text `codeloupe metrics` prints: one block per role, and the change of the medians between two reports. */
object MetricsRender {
    private const val CATEGORIES_SHOWN = 7
    private const val COMMANDS_SHOWN = 10

    fun summary(aggregate: Map<String, RoleAggregate>, roles: Set<String>? = null): String {
        val lines = ArrayList<String>()
        for ((role, a) in aggregate.entries.sortedByDescending { it.value.cost.sum }) {
            if (roles != null && role !in roles) continue
            lines += "$role  runs ${a.runs}  cost med ${fmt(a.cost.median)} p75 ${fmt(a.cost.p75)} Σ ${fmt(a.cost.sum)}  peakCtx med ${fmt(a.peakContext.median)}  " +
                "turns med ${a.turns.median}  wall med ${a.wallSec.median}s  codeRead med ${a.codeReadCalls.median} calls / ${fmt(a.codeReadChars.median)} ch, " +
                "rereads med ${a.codeRereads.median}, editErr Σ ${a.codeEditErrors.sum}/${a.codeEditCalls.sum}"
            a.start?.let { lines += StartRender.line(it) }
            lines += "   tool results = ${percent(a.toolResultCostPct)}% of cost: " +
                a.categories.entries.take(CATEGORIES_SHOWN).joinToString("  ") { (c, v) -> "$c ${percent(v.costPct)}%" }
            if (roles != null) a.topCommands.take(COMMANDS_SHOWN).forEach { lines += "     ${percent(it.costPct)}%  ${it.calls}×  ${it.cmd}" }
        }
        return lines.joinToString("\n")
    }

    fun compare(a: Map<String, RoleAggregate>, b: Map<String, RoleAggregate>): String {
        val lines = ArrayList<String>()
        for (role in b.keys.filter { it in a }) {
            val x = a.getValue(role)
            val y = b.getValue(role)
            lines += "$role  runs ${x.runs} -> ${y.runs}"
            val figures = listOf(
                "cost" to (x.cost to y.cost), "peakContext" to (x.peakContext to y.peakContext), "turns" to (x.turns to y.turns),
                "wallSec" to (x.wallSec to y.wallSec), "codeReadCalls" to (x.codeReadCalls to y.codeReadCalls),
                "codeReadChars" to (x.codeReadChars to y.codeReadChars), "codeRereads" to (x.codeRereads to y.codeRereads),
                "codeEditErrors" to (x.codeEditErrors to y.codeEditErrors), "toolErrors" to (x.toolErrors to y.toolErrors),
            )
            lines += StartRender.compare(x.start, y.start)
            lines += "   " + figures.joinToString("  ") { (name, pair) -> change(name, pair.first.median, pair.second.median) }
        }
        return lines.joinToString("\n")
    }

    private fun change(name: String, before: Long, after: Long): String {
        val delta = if (before != 0L) " (${if (after >= before) "+" else ""}${Math.round(100.0 * (after - before) / before)}%)" else ""
        return "$name ${fmt(before)}→${fmt(after)}$delta"
    }

    fun fmt(n: Long): String = when {
        n >= 1_000_000 -> Tenths.text(n / 1e6) + "M"
        n >= 1_000 -> Tenths.text(n / 1e3) + "k"
        else -> n.toString()
    }

    internal fun percent(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
}
