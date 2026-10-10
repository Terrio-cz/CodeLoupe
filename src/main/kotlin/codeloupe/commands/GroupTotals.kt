package codeloupe.commands

import codeloupe.platform.Tenths

/** The running sums of one command group in one tally; [add] is called run by run, so a run's share can be settled when the next run starts. */
internal class GroupTotals(private val kind: String, private val trackRuns: Boolean) {
    private var runs = 0
    private var argChars = 0L
    private var repeats = 0
    private var repeatChars = 0L
    private var errors = 0
    private var money = 0.0
    private val top = ArrayList<CommandRunRef>()

    var calls = 0
        private set
    var chars = 0L
        private set
    var weighted = 0L
        private set

    private var current: CommandRun? = null
    private var currentCalls = 0
    private var currentWeighted = 0L

    fun add(run: CommandRun, step: CommandStep, argChars: Long, repeat: Boolean, carried: Long, money: Double) {
        if (current?.id != run.id) {
            settle()
            current = run
            runs++
        }
        calls++
        chars += step.chars
        this.argChars += argChars
        if (repeat) {
            repeats++
            repeatChars += step.chars
        }
        if (step.error) errors++
        weighted += carried
        this.money += money
        currentCalls++
        currentWeighted += carried
    }

    /** Settles the run last added, then reads the sums as a [CommandGroup] of the period that cost [cost] and has a price when [priced]. */
    fun group(command: String, cost: Long, priced: Boolean): CommandGroup {
        settle()
        return CommandGroup(
            command, kind, calls, runs, CommandCost.tokens(chars), CommandCost.tokens(argChars), repeats, CommandCost.tokens(repeatChars), errors,
            weighted, Tenths.of(100.0 * weighted / maxOf(cost, 1)), if (priced) money else null, top.toList(),
        )
    }

    private fun settle() {
        val run = current ?: return
        if (trackRuns && currentCalls > 0) {
            top += CommandRunRef(run.id.toString(), run.role, run.ter, run.title, currentCalls, currentWeighted)
            top.sortByDescending { it.weighted }
            while (top.size > TOP_RUNS) top.removeLast()
        }
        currentCalls = 0
        currentWeighted = 0
        current = null
    }

    private companion object {
        const val TOP_RUNS = 3
    }
}
