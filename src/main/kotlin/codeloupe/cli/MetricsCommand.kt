package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe metrics …` — token and speed figures of agent runs, read from Claude Code transcripts. */
class MetricsCommand : CliktCommand(name = "metrics") {
    init {
        subcommands(MetricsCollectCommand(), MetricsCompareCommand(), MetricsGapsCommand(), MetricsBoilerplateCommand(), MetricsHooksCommand(), MetricsOrientationCommand(), MetricsWeightCommand())
    }

    override fun help(context: Context) = "Measure agent runs from Claude Code transcripts: collect, compare, gaps, boilerplate, hooks, orientation, weight."

    override fun run() = Unit
}
