package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.metrics.MetricsCollector
import codeloupe.metrics.MetricsRender
import codeloupe.metrics.MetricsReport
import codeloupe.metrics.MetricsSetup
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class MetricsCollectCommand : CliktCommand(name = "collect") {
    private val since by option(help = "First day or instant to include, e.g. 2026-09-23").required()
    private val until by option(help = "Last instant to include")
    private val label by option(help = "Name of the report (default: run)").default("run")
    private val roles by option(help = "Comma-separated roles to print in detail, with their top commands")
    private val dir by option(help = "Transcript project directory (repeatable; default: all under ~/.claude/projects)").multiple()
    private val out by option(help = "Report file (default: <label>-<date>.json)")

    override fun help(context: Context) = "Read the transcripts of a period and write one JSON report."

    override fun run() {
        val config = ConfigLoader.load()
        val setup = MetricsSetup(config)
        val report = MetricsCollector(setup.categorizer()).collect(setup.projectDirs(dir), label, MetricsSetup.instant(since), until?.let(MetricsSetup::instant))
        val file = Path.of(out ?: "$label-${LocalDate.now()}.json")
        Files.writeString(file, PRETTY.encodeToString(MetricsReport.serializer(), report) + "\n")
        echo(MetricsRender.summary(report.aggregate, roles?.split(',')?.toSet()))
        echo("runs ${report.runs.size} -> $file")
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json(JsonFormat.json) {
            prettyPrint = true
            prettyPrintIndent = " "
        }
    }
}
