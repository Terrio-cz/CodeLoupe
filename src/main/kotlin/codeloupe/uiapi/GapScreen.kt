package codeloupe.uiapi

import codeloupe.ingest.GapRecord
import codeloupe.ingest.StepText
import codeloupe.ingest.Transcripts
import codeloupe.platform.IsoTime
import java.time.Instant

/**
 * The Gaps screen: where an agent asked CodeLoupe and then read, searched or gave up (detector of CL-22), from the ingested
 * transcripts. Calls answered "busy" are the daemon's load, not a gap in the answer, and are left out of [Gaps.summary] and
 * [Gaps.items]. The weekly [Gaps.report] counts them like `codeloupe metrics gaps` does, over the last 30 days. Until a first
 * transcript is ingested, the report is the one [stored] reads from the file `codeloupe metrics gaps --out` wrote.
 */
internal class GapScreen(private val transcripts: Transcripts, private val stored: GapViews, private val clock: () -> Instant = Instant::now) {
    suspend fun gaps(range: String?, tool: String?, reason: String?): Gaps {
        val now = clock()
        val from = now.minusSeconds(Ranges.days(range ?: "7d") * 86_400).toEpochMilli()
        val kinds = reason?.let { listOf(KIND_OF[it] ?: throw UiApiException.badRequest("reason must be one of ${KIND_OF.keys.joinToString()}")) }.orEmpty()
        transcripts.fresh()
        val queries = transcripts.queries
        return Gaps(
            summary = queries.gapGroups(from, tool, kinds).map { Gaps.Summary(it.tool, it.shape, it.fallback, it.count, iso(it.lastMs)) },
            items = queries.gaps(from, tool, kinds, MAX_ITEMS).map(::item),
            report = report(now),
        )
    }

    private fun item(g: GapRecord) = Gaps.Item(
        id = g.id.toString(), at = iso(g.atMs), tool = g.tool, shape = g.shape, fallback = g.fallback ?: "other",
        reason = REASON_OF[g.kind] ?: "empty", session = g.session, turn = g.turn, target = StepText.clean(g.token.orEmpty()),
    )

    private fun report(now: Instant): Gaps.Report? {
        val since = now.minusSeconds(REPORT_DAYS * 86_400)
        val (runs, calls) = transcripts.queries.runsAndCalls(since.toEpochMilli())
        if (runs == 0) return stored.gaps().report
        val rows = transcripts.queries.gapReport(since.toEpochMilli()).groupBy { it.take(4) }.map { (key, same) ->
            Gaps.Row(key[0]!!, key[1]!!, key[2]!!, key[3]!!, same.size, same.mapNotNull { it[4] }.distinct().take(EXAMPLES).map { StepText.clean(it) })
        }.sortedWith(compareBy<Gaps.Row> { it.week }.thenByDescending { it.count }.thenBy { it.shape })
        return Gaps.Report(IsoTime.of(now), since.toString(), runs, calls, rows)
    }

    private fun iso(ms: Long) = IsoTime.of(Instant.ofEpochMilli(ms))

    private companion object {
        const val MAX_ITEMS = 200
        const val REPORT_DAYS = 30L
        const val EXAMPLES = 3
        val KIND_OF = mapOf("followup_read" to "fallback", "empty" to "empty", "candidate_manual" to "candidates")
        val REASON_OF = KIND_OF.entries.associate { (reason, kind) -> kind to reason }
    }
}
