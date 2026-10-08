package codeloupe.uiapi

import codeloupe.ingest.GapRecord
import codeloupe.ingest.StepText
import codeloupe.ingest.Transcripts
import codeloupe.platform.IsoTime
import java.time.Instant

/**
 * The Gaps screen from the ingested transcripts (detector of CL-22, `codeloupe metrics gaps`): where an agent asked CodeLoupe
 * and then read, searched or gave up. Calls answered "busy" are the daemon's load, not a gap in the answer, and are left out.
 */
internal class GapViews(private val transcripts: Transcripts, private val clock: () -> Instant = Instant::now) {
    suspend fun gaps(range: String?, tool: String?, reason: String?): Gaps {
        val from = clock().minusSeconds(Ranges.days(range ?: "7d") * 86_400).toEpochMilli()
        val kinds = reason?.let { listOf(KIND_OF[it] ?: throw UiApiException.badRequest("reason must be one of ${KIND_OF.keys.joinToString()}")) }.orEmpty()
        transcripts.fresh()
        val queries = transcripts.queries
        return Gaps(
            summary = queries.gapGroups(from, tool, kinds).map { Gaps.Summary(it.tool, it.shape, it.fallback, it.count, iso(it.lastMs)) },
            items = queries.gaps(from, tool, kinds, MAX_ITEMS).map(::item),
        )
    }

    private fun item(g: GapRecord) = Gaps.Item(
        id = g.id.toString(), at = iso(g.atMs), tool = g.tool, shape = g.shape, fallback = g.fallback ?: "other",
        reason = REASON_OF[g.kind] ?: "empty", session = g.session, turn = g.turn, target = StepText.clean(g.token.orEmpty()),
    )

    private fun iso(ms: Long) = IsoTime.of(Instant.ofEpochMilli(ms))

    private companion object {
        const val MAX_ITEMS = 200
        val KIND_OF = mapOf("followup_read" to "fallback", "empty" to "empty", "candidate_manual" to "candidates")
        val REASON_OF = KIND_OF.entries.associate { (reason, kind) -> kind to reason }
    }
}
