package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe metrics …` — token and speed figures of agent runs, read from Claude Code transcripts. */
class MetricsCommand : CliktCommand(name = "metrics") {
    init {
        subcommands(MetricsCollectCommand(), MetricsCompareCommand(), MetricsWhatIfCommand(), MetricsGapsCommand(), MetricsBoilerplateCommand(), MetricsHooksCommand())
    }

    override fun help(context: Context) = "Measure agent runs from Claude Code transcripts: collect, compare, what-if, gaps, boilerplate, hooks."

    override fun run() = Unit
}
