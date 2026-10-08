package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.metrics.MetricsRender
import codeloupe.metrics.MetricsReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import java.nio.file.Files
import java.nio.file.Path

class MetricsCompareCommand : CliktCommand(name = "compare") {
    private val before by argument(help = "Earlier report")
    private val after by argument(help = "Later report")

    override fun help(context: Context) = "Print the change of every role's medians between two reports."

    override fun run() {
        fun load(file: String) = JsonFormat.json.decodeFromString(MetricsReport.serializer(), Files.readString(Path.of(file)))
        echo(MetricsRender.compare(load(before).aggregate, load(after).aggregate))
    }
}
