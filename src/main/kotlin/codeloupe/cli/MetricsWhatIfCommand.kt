package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.metrics.MetricsMoney
import codeloupe.metrics.MoneyRender
import codeloupe.metrics.MetricsReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path

class MetricsWhatIfCommand : CliktCommand(name = "what-if") {
    private val report by argument(help = "Report written by `metrics collect`")
    private val roles by option(help = "Comma-separated roles (default: the five dearest)")
    private val models by option(help = "Comma-separated model ids to price the runs on (default: the cheaper ones of the price table)")

    override fun help(context: Context) =
        "What each role's runs would cost on other models, with their token counts (an upper bound, see the caveat it prints)."

    override fun run() {
        val prices = ConfigLoader.load().metrics.prices
        val runs = JsonFormat.json.decodeFromString(MetricsReport.serializer(), Files.readString(Path.of(report))).runs
        val dearest = MetricsMoney.byRole(runs, prices).entries.sortedByDescending { it.value.priced }.map { it.key }
        val chosenRoles = roles?.split(',')?.map(String::trim)?.filter { it.isNotEmpty() } ?: dearest.take(DEFAULT_ROLES)
        val chosenModels = models?.split(',')?.map(String::trim)?.filter { it.isNotEmpty() }
            ?: prices.models.entries.sortedBy { it.value.input + it.value.output }.map { it.key }.take(DEFAULT_MODELS)
        chosenModels.firstOrNull { it !in prices.models }?.let { throw UsageError("no price for $it: add it under config.json metrics.prices.models") }
        echo(MoneyRender.whatIf(runs, prices, chosenRoles, chosenModels))
    }

    private companion object {
        const val DEFAULT_ROLES = 5
        const val DEFAULT_MODELS = 3
    }
}
