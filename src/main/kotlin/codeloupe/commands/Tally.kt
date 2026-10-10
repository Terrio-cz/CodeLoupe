package codeloupe.commands

import codeloupe.platform.Tenths

/** The groups of one slice of a period (everything, one role, one week) with the cost of the runs in it. */
internal class Tally(private val trackRuns: Boolean = false) {
    private val groups = HashMap<String, GroupTotals>()
    private var runs = 0
    private var calls = 0
    private var chars = 0L
    private var weighted = 0L

    var cost = 0L
        private set

    val groupCount: Int get() = groups.size

    fun startRun(run: CommandRun) {
        runs++
        cost += run.cost
    }

    fun add(label: CommandLabel, run: CommandRun, step: CommandStep, argChars: Long, repeat: Boolean, carried: Long, money: Double) {
        groups.getOrPut(label.label) { GroupTotals(label.kind, trackRuns) }.add(run, step, argChars, repeat, carried, money)
        calls++
        chars += step.chars
        weighted += carried
    }

    fun top(n: Int, sort: CommandSort, priced: Boolean): List<CommandGroup> =
        sort.ordered(groups.map { (command, totals) -> totals.group(command, cost, priced) }).take(n)

    fun slice(key: String, n: Int, sort: CommandSort, priced: Boolean) =
        CommandSlice(key, runs, calls, CommandCost.tokens(chars), cost, Tenths.of(100.0 * weighted / maxOf(cost, 1)), top(n, sort, priced))
}
