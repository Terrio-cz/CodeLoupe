package codeloupe.uiapi

import codeloupe.JsonFormat
import codeloupe.events.Scrubber
import codeloupe.metrics.GapReport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The Gaps screen: the weekly report that `codeloupe metrics gaps --out <home>/gaps-report.json` wrote. Reading the
 * transcripts takes seconds to minutes, so the daemon never does it for a request; it serves the file, or an empty report
 * while there is none. The examples are what an agent asked for, so they pass the same scrubber as every outgoing text.
 */
internal class GapViews(private val file: Path) {
    fun gaps(): Gaps = Gaps(emptyList(), emptyList(), report())

    private fun report(): Gaps.Report? {
        val stored = runCatching { JsonFormat.json.decodeFromString(GapReport.serializer(), Files.readString(file)) }.getOrNull() ?: return null
        val written = stored.generatedAt ?: runCatching { Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString() }.getOrNull()
        val rows = stored.rows.map { Gaps.Row(it.week, it.tool, it.shape, it.kind, it.count, it.examples.map(Scrubber::text)) }
        return Gaps.Report(written, stored.since, stored.runs, stored.calls, rows)
    }

    companion object {
        const val FILE = "gaps-report.json"
    }
}
