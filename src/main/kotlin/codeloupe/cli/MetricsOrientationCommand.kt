package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.hooks.OrientationScan
import codeloupe.metrics.MetricsSetup
import codeloupe.metrics.TranscriptFinder
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class MetricsOrientationCommand : CliktCommand(name = "orientation") {
    private val since by option(help = "First day or instant to include, e.g. 2026-10-01").required()
    private val until by option(help = "Last instant to include")
    private val dir by option(help = "Transcript project directory (repeatable; default: all under ~/.claude/projects)").multiple()
    private val turns by option(help = "How many of the first turns of a session count (default 8)").int()

    override fun help(context: Context) = "ls, find, tree and Glob calls in the first turns of sessions, with and without the session-start hook's context."

    override fun run() {
        val config = ConfigLoader.load()
        val files = TranscriptFinder(MetricsSetup.instant(since), until?.let(MetricsSetup::instant)).find(MetricsSetup(config).projectDirs(dir))
        echo(OrientationScan(turns ?: OrientationScan.TURNS).run(files).render())
    }
}
