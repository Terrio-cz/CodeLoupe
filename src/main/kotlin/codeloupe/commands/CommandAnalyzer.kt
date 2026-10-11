package codeloupe.commands

import codeloupe.metrics.PriceTable
import codeloupe.platform.IsoTime
import codeloupe.platform.Tenths
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Builds a [CommandReport] from the stored runs and steps, one run at a time. What a command carried is what `metrics collect` calls
 * the cost of tool results ([CommandCost.carried]); its money is the same share of what the run cost at [prices].
 */
class CommandAnalyzer(private val source: CommandSource, private val prices: PriceTable, private val now: () -> Instant = Instant::now) {
    private class CategoryTotals {
        var calls = 0
        var chars = 0L
        var errors = 0
        var weighted = 0L
    }

    fun report(filter: CommandFilter, top: Int, sort: CommandSort = CommandSort.COST): CommandReport {
        val runs = source.runs(filter)
        val all = Tally(trackRuns = true)
        val roles = HashMap<String, Tally>()
        val weeks = HashMap<String, Tally>()
        val categories = HashMap<String, CategoryTotals>()
        var argChars = 0L
        var money = 0.0
        var unpriced = 0
        for (run in runs) {
            val price = prices.priceOf(run.model)
            if (price == null) unpriced++
            val perWeighted = price?.cost(run.usage)?.let { it / maxOf(run.cost, 1) }
            val tallies = listOf(all, roles.getOrPut(run.role) { Tally() }, weeks.getOrPut(week(run.startMs)) { Tally() })
            tallies.forEach { it.startRun(run) }
            val normalizer = CommandNormalizer()
            val seen = HashSet<String>()
            for (step in source.steps(run.id)) {
                val label = normalizer.of(step.tool, step.summary, step.head, step.error, step.command)
                val carried = CommandCost.carried(step.chars, run.turns - step.turn)
                val stepMoney = perWeighted?.times(carried) ?: 0.0
                val sent = step.inputChars ?: step.summary.length.toLong()
                val repeat = !seen.add(step.tool + "\u0000" + (step.inputHash?.toString() ?: step.summary))
                tallies.forEach { it.add(label, run, step, sent, repeat, carried, stepMoney) }
                argChars += sent
                money += stepMoney
                categories.getOrPut(step.category) { CategoryTotals() }.also { c ->
                    c.calls++
                    c.chars += step.chars
                    if (step.error) c.errors++
                    c.weighted += carried
                }
            }
        }
        val priced = runs.isNotEmpty() && unpriced < runs.size
        val cost = runs.sumOf { it.cost }
        val groups = all.top(top, sort, priced)
        val weighted = categories.values.sumOf { it.weighted }
        return CommandReport(
            generatedAt = IsoTime.of(now()), since = Instant.ofEpochMilli(filter.fromMs).toString(), until = filter.toMs?.let { Instant.ofEpochMilli(it).toString() },
            role = filter.role, runs = runs.size, calls = categories.values.sumOf { it.calls }, resultTokens = CommandCost.tokens(categories.values.sumOf { it.chars }),
            argTokens = CommandCost.tokens(argChars), cost = cost, weighted = weighted, costPct = percent(weighted, cost), money = if (priced) money else null,
            currency = prices.currency, unpricedRuns = unpriced, groupCount = all.groupCount, groups = groups,
            categories = categories.entries.sortedWith(compareByDescending<Map.Entry<String, CategoryTotals>> { it.value.weighted }.thenBy { it.key }).map { (name, c) ->
                CommandCategory(name, c.calls, CommandCost.tokens(c.chars), c.errors, c.weighted, percent(c.weighted, cost))
            },
            roles = roles.entries.sortedByDescending { it.value.cost }.map { (key, tally) -> tally.slice(key, top, sort, priced) },
            weeks = weeks.entries.sortedBy { it.key }.map { (key, tally) -> tally.slice(key, top, sort, priced) },
        )
    }

    private fun percent(part: Long, whole: Long) = Tenths.of(100.0 * part / maxOf(whole, 1))

    private fun week(startMs: Long): String {
        val date = Instant.ofEpochMilli(startMs).atOffset(ZoneOffset.UTC)
        return "%d-W%02d".format(java.util.Locale.ROOT, date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}
