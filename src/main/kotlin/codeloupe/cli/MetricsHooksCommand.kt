package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.config.HooksConfig
import codeloupe.hooks.HookReplay
import codeloupe.hooks.HookUsage
import codeloupe.metrics.MetricsSetup
import codeloupe.metrics.TranscriptFinder
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class MetricsHooksCommand : CliktCommand(name = "hooks") {
    private val since by option(help = "First day or instant to include, e.g. 2026-10-01")
    private val until by option(help = "Last instant to include (replay)")
    private val dir by option(help = "Transcript project directory for --replay (repeatable; default: all under ~/.claude/projects)").multiple()
    private val replay by option(help = "Run the shell and read calls of old transcripts through the steering hook instead of reporting the live log").flag()
    private val mode by option(help = "Replay in this mode (advise, redirect); default is the configured one")
    private val minLines by option(help = "Replay with this large-file threshold in lines").int()
    private val giveUpAfter by option(help = "Replay with this many unheeded pieces of advice in a row before the hook goes quiet (a large number: as if every advice were taken)").int()
    private val maxPerSession by option(help = "Replay with this many pieces of advice per session at most").int()

    override fun help(context: Context) =
        "How often the steering hook spoke and how often a CodeLoupe call followed; --replay: what it would say to old transcripts."

    override fun run() {
        val config = ConfigLoader.load()
        val from = since?.let(MetricsSetup::instant)
        if (!replay) {
            echo(HookUsage(config.home).since(from).render())
            return
        }
        val configured = ConfigLoader.hooks(config.home).steer
        val steer = HooksConfig.SteerConfig(
            mode ?: configured.mode.takeIf { it != HooksConfig.OFF } ?: HooksConfig.ADVISE, minLines ?: configured.minLines,
            maxPerSession ?: configured.maxPerSession, giveUpAfter ?: configured.giveUpAfter,
        )
        val files = TranscriptFinder(from, until?.let(MetricsSetup::instant)).find(MetricsSetup(config).projectDirs(dir))
        echo(HookReplay(HooksConfig(steer = steer)).run(files).render())
    }
}
