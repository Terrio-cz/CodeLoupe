package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.commands.CommandPeriod
import codeloupe.config.ConfigLoader
import codeloupe.ingest.LimitSnapshots
import codeloupe.ingest.TokenMinute
import codeloupe.ingest.TokenMinuteCsv
import codeloupe.ingest.TokenMinutes
import codeloupe.ingest.TranscriptDb
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.time.Instant

class MetricsTokensCommand : CliktCommand(name = "tokens") {
    private val since by option(help = "Start of the period: 10d, 24h, a day such as 2026-10-01, or an instant (default 30d)").default("30d")
    private val until by option(help = "End of the period: a day or an instant (default now)")
    private val account by option(help = "Only this account id; 'unknown' for the runs whose account is not decidable")
    private val max by option("--max", help = "At most this many rows, the newest ones (default 2000000)").int().default(2_000_000)
    private val format by option(help = "jsonl (default), csv or json").choice("jsonl", "csv", "json").default("jsonl")

    override fun help(context: Context) =
        "Export the tokens agent sessions spent per minute, model and run (input, cache writes 5m and 1h, cache read, output and the thinking part of it) with the account, " +
            "for calibrating the subscription limits against the /usage snapshots of 'metrics limits'. Reads <home>/transcripts.db; numbers and names only, no conversation text."

    override fun run() {
        if (max < 1) throw CliktError("--max must be at least 1")
        val file = ConfigLoader.load().home.resolve("transcripts.db")
        if (!Files.exists(file)) throw CliktError("no transcript database at $file: the daemon reads the transcripts when the desktop app (or any /ui-api/v1 call) asks for runs, so open the Runs screen once")
        val now = Instant.now()
        val (from, to) = try {
            CommandPeriod.since(since, now).toEpochMilli() to (until?.let { CommandPeriod.since(it, now).toEpochMilli() } ?: Long.MAX_VALUE)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message)
        }
        val who = when (account) {
            null -> LimitSnapshots.Who.All
            "unknown" -> LimitSnapshots.Who.Unknown
            else -> LimitSnapshots.Who.Account(account!!)
        }
        val rows: List<TokenMinute> = TranscriptDb(file).use { TokenMinutes(it).between(from, to, who, max) }
        when (format) {
            "csv" -> echo(TokenMinuteCsv.render(rows), trailingNewline = false)
            "json" -> echo(JsonFormat.json.encodeToString(ListSerializer(TokenMinute.serializer()), rows))
            else -> rows.forEach { echo(JsonFormat.json.encodeToString(TokenMinute.serializer(), it)) }
        }
    }
}
