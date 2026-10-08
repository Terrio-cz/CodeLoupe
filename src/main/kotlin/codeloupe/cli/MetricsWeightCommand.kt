package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.hooks.WeightReplay
import codeloupe.metrics.MetricsSetup
import codeloupe.metrics.TranscriptFinder
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class MetricsWeightCommand : CliktCommand(name = "weight") {
    private val since by option(help = "First day or instant to include, e.g. 2026-10-01").required()
    private val until by option(help = "Last instant to include")
    private val dir by option(help = "Transcript project directory (repeatable; default: all under ~/.claude/projects)").multiple()
    private val longest by option(help = "How many of the longest sessions to replay (default 20)").int()

    override fun help(context: Context) = "Replay the longest sessions through the session-weight verdict: at which turn each would have been warned, and what the heaviest results held."

    override fun run() {
        val config = ConfigLoader.load()
        val weight = ConfigLoader.hooks(config.home).weight
        val files = TranscriptFinder(MetricsSetup.instant(since), until?.let(MetricsSetup::instant)).find(MetricsSetup(config).projectDirs(dir))
        echo(WeightReplay(weight.warnAt, weight.top).run(files, longest ?: 20).render())
    }
}
