package codeloupe.metrics

/** Sums run summaries up per role. */
object Aggregator {
    private const val TOP_COMMANDS = 15

    fun aggregate(runs: List<RunSummary>): Map<String, RoleAggregate> =
        runs.groupBy { it.role }.mapValues { (_, rs) -> role(rs) }

    private fun role(rs: List<RunSummary>): RoleAggregate {
        val cats = LinkedHashMap<String, CatStats>()
        val cmds = LinkedHashMap<String, CmdStats>()
        for (r in rs) {
            r.byCat.forEach { (c, v) -> cats.merge(c, v, CatStats::plus) }
            r.cmds.forEach { (k, v) -> cmds.merge(k, v) { a, b -> CmdStats(a.calls + b.calls, a.attr + b.attr) } }
        }
        val totalChars = cats.values.sumOf { it.chars }
        val totalCarried = cats.values.sumOf { it.carried }
        val costSum = rs.sumOf { it.cost }
        fun pct(part: Long, whole: Long) = tenth(100.0 * part / (if (whole == 0L) 1 else whole))
        fun pick(f: (RunSummary) -> Long) = Pick.of(rs.map(f))
        return RoleAggregate(
            runs = rs.size, cost = pick { it.cost }, output = pick { it.usage.output }, cacheRead = pick { it.usage.cacheRead },
            cacheWrite = pick { it.usage.cw5m + it.usage.cw1h }, peakContext = pick { it.peakContext }, turns = pick { it.turns.toLong() },
            wallSec = pick { it.wallSec }, toolSec = pick { it.toolSec }, codeReadCalls = pick { it.codeRead.calls.toLong() },
            codeReadChars = pick { it.codeRead.chars }, codeRereads = pick { it.codeRead.rereads.toLong() },
            wholeFileReads = pick { it.codeRead.whole.toLong() }, codeEditCalls = pick { it.codeEdit.calls.toLong() },
            codeEditErrors = pick { it.codeEdit.errors.toLong() }, toolErrors = pick { it.toolErrors.toLong() },
            toolResultCostPct = pct(cats.values.sumOf { it.attr }, costSum),
            topCommands = cmds.entries.sortedByDescending { it.value.attr }.take(TOP_COMMANDS)
                .map { (k, v) -> TopCommand(k, v.calls, pct(v.attr, costSum)) },
            categories = cats.entries.sortedByDescending { it.value.carried }.associate { (c, v) ->
                c to CategoryShare(v.calls, v.chars, v.carried, v.attr, v.errors, v.ms, pct(v.chars, totalChars), pct(v.carried, totalCarried), pct(v.attr, costSum))
            },
        )
    }

    private fun tenth(x: Double) = Math.round(x * 10) / 10.0
}
