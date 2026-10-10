package codeloupe.commands

import codeloupe.ingest.TranscriptDb
import codeloupe.metrics.PriceTable
import java.nio.file.Path
import java.time.Instant

/** Reads a [CommandReport] from the transcript database file of a daemon home, the way `codeloupe metrics commands` does. */
object CommandReports {
    fun read(file: Path, prices: PriceTable, filter: CommandFilter, top: Int, sort: CommandSort, now: Instant): CommandReport =
        TranscriptDb(file).use { db -> CommandAnalyzer(CommandSource(db), prices) { now }.report(filter, top, sort) }
}
