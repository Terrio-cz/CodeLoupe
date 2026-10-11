package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.commands.CommandPeriod
import codeloupe.config.ConfigLoader
import codeloupe.ingest.LimitCsv
import codeloupe.ingest.LimitSnapshot
import codeloupe.ingest.LimitSnapshots
import codeloupe.ingest.TranscriptDb
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.time.Instant

class MetricsLimitsCommand : CliktCommand(name = "limits") {
    private val since by option(help = "Start of the period: 10d, 24h, a day such as 2026-10-01, or an instant (default 30d)").default("30d")
    private val until by option(help = "End of the period: a day or an instant (default now)")
    private val account by option(help = "Only this account id; 'unknown' for the snapshots whose account is not decidable")
    private val latest by option("--latest", help = "Only the newest snapshot of every meter per account, whatever the period").flag()
    private val max by option("--max", help = "At most this many snapshots, the newest ones (default 100000)").int().default(100_000)
    private val format by option(help = "jsonl (default), csv or json").choice("jsonl", "csv", "json").default("jsonl")

    override fun help(context: Context) =
        "Export the /usage limits that agent sessions reported (5-hour, weekly, weekly per model): when, percent, reset time, severity and the account. " +
            "Reads the transcript database the daemon fills (<home>/transcripts.db); snapshots are kept even after Claude Code deletes the transcript. " +
            "Metadata only, no conversation text."

    override fun run() {
        if (max < 1) throw CliktError("--max must be at least 1")
        val config = ConfigLoader.load()
        val file = config.home.resolve("transcripts.db")
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
        val rows: List<LimitSnapshot> = TranscriptDb(file).use { db ->
            val snapshots = LimitSnapshots(db)
            if (latest) snapshots.latest(who) else snapshots.between(from, to, who, max)
        }
        when (format) {
            "csv" -> echo(LimitCsv.render(rows), trailingNewline = false)
            "json" -> echo(JsonFormat.json.encodeToString(ListSerializer(LimitSnapshot.serializer()), rows))
            else -> rows.forEach { echo(JsonFormat.json.encodeToString(LimitSnapshot.serializer(), it)) }
        }
    }
}
