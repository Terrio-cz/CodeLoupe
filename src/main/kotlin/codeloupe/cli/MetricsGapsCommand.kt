package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.metrics.GapReport
import codeloupe.metrics.GapRule
import codeloupe.metrics.MetricsCollector
import codeloupe.metrics.MetricsSetup
import codeloupe.platform.IsoTime
import codeloupe.write.WriteGate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path

class MetricsGapsCommand : CliktCommand(name = "gaps") {
    private val since by option(help = "First day or instant to include, e.g. 2026-10-01").required()
    private val until by option(help = "Last instant to include")
    private val dir by option(help = "Transcript project directory (repeatable; default: all under ~/.claude/projects)").multiple()
    private val out by option(help = "Also write the report as JSON to this file")
    private val all by option("--all", help = "Count the way the series began: every empty answer is a gap and any later search for the same word a fallback").flag()

    override fun help(context: Context) =
        "Where CodeLoupe calls fell short: fallbacks to rg/cat/Read, busy and candidate-only answers, and empty ones the agent looked up elsewhere, by week and query shape. " +
            "Empty answers it accepted are counted apart as settled negatives."

    override fun run() {
        val config = ConfigLoader.load()
        val setup = MetricsSetup(config)
        val collector = MetricsCollector(setup.categorizer())
        val runs = collector.runs(setup.projectDirs(dir), MetricsSetup.instant(since), until?.let(MetricsSetup::instant))
        val report = GapReport.of(runs, if (all) GapRule.LEGACY else GapRule.CURRENT).covering(since, IsoTime.now())
        out?.let { Files.writeString(Path.of(it), JsonFormat.json.encodeToString(GapReport.serializer(), report) + "\n") }
        echo(report.render())
        val gate = WriteGate(config.write, config.home.resolve("write-gate.json")) { from -> collector.runs(setup.projectDirs(dir), from, null) }
        echo(gate.evaluate().render(config.write.gate) + " - edit is offered " + when (config.write.mode) { "on" -> "always (write.mode on)"; "off" -> "never (write.mode off)"; else -> "when the gate is open (write.mode auto)" })
    }
}
