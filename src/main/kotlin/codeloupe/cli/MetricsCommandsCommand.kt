package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.commands.CommandFilter
import codeloupe.commands.CommandPeriod
import codeloupe.commands.CommandReport
import codeloupe.commands.CommandReportRender
import codeloupe.commands.CommandReports
import codeloupe.commands.CommandSort
import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Files
import java.time.Instant

class MetricsCommandsCommand : CliktCommand(name = "commands") {
    private val since by option(help = "Start of the period: 10d, 24h, a day such as 2026-10-01, or an instant (default 7d)").default("7d")
    private val until by option(help = "End of the period: a day or an instant (default now)")
    private val role by option(help = "Only runs of this role, e.g. coder")
    private val run by option(help = "Only one run: its id (see the Runs screen), file name or session id")
    private val top by option(help = "How many groups to list (default 15)").int().default(15)
    private val sort by option(help = "Rank by cost (default), calls or tokens").choice(*CommandSort.entries.map { it.param }.toTypedArray()).default(CommandSort.COST.param)
    private val json by option("--json", help = "Print the report as JSON").flag()

    override fun help(context: Context) =
        "Which commands agents use most and what they cost: calls, result and argument tokens, identical repeats, errors and the share of the cost their results carry, by role and by week. " +
            "Reads the transcript database the daemon fills (<home>/transcripts.db), so it shows what the desktop app's Runs screen shows."

    override fun run() {
        if (top < 1) throw CliktError("--top must be at least 1")
        val config = ConfigLoader.load()
        val file = config.home.resolve("transcripts.db")
        if (!Files.exists(file)) throw CliktError("no transcript database at $file: the daemon reads the transcripts when the desktop app (or any /ui-api/v1 call) asks for runs, so open the Runs screen once")
        val now = Instant.now()
        val filter = try {
            CommandFilter(CommandPeriod.since(since, now).toEpochMilli(), until?.let { CommandPeriod.since(it, now).toEpochMilli() }, role, run)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message)
        }
        val report = CommandReports.read(file, config.metrics.prices, filter, top, CommandSort.of(sort)!!, now)
        echo(if (json) JsonFormat.json.encodeToString(CommandReport.serializer(), report) else CommandReportRender.text(report))
    }
}
