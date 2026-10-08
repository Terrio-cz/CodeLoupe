package codeloupe.uiapi

import codeloupe.ingest.RunRecord
import codeloupe.ingest.RunSort
import codeloupe.ingest.StepRecord
import codeloupe.ingest.StepSort
import codeloupe.ingest.Transcripts
import codeloupe.platform.IsoTime
import java.time.Instant

/** The Runs screen from the ingested transcripts: the list, one run and its steps. Every answer is an indexed read of the daemon's SQLite. */
internal class RunViews(private val transcripts: Transcripts, private val clock: () -> Instant = Instant::now) {
    suspend fun page(range: String?, sort: String?, role: String?, q: String?, limit: String?, cursor: String?): RunPage {
        val from = clock().minusSeconds(Ranges.days(range ?: "7d") * 86_400)
        val order = sort?.let { RunSort.of(it) ?: throw UiApiException.badRequest("sort must be one of ${RunSort.entries.joinToString { it.param }}") } ?: RunSort.START
        val size = OffsetCursor.limit(limit, DEFAULT_RUNS, MAX_RUNS)
        val offset = OffsetCursor.offset(cursor)
        transcripts.fresh()
        val page = transcripts.queries.runs(from.toEpochMilli(), role, q?.trim()?.takeIf { it.isNotEmpty() }, order, offset, size)
        val next = (offset + size).takeIf { it < page.total }?.let(OffsetCursor::of)
        return RunPage(page.items.map(::item), page.total, next, transcripts.queries.roles(), transcripts.ingest.status())
    }

    suspend fun detail(id: String): RunDetail {
        transcripts.fresh()
        val run = id.toLongOrNull()?.let(transcripts.queries::run) ?: throw UiApiException.notFound("no run $id")
        val u = run.usage
        return RunDetail(
            item(run), RunDetail.Usage(u.input, u.cw5m, u.cw1h, u.cacheRead, u.output),
            transcripts.queries.categories(run.id).map { RunDetail.Category(it.category, it.calls, it.chars, it.carried, it.weighted, it.errors) },
        )
    }

    suspend fun steps(id: String, sort: String?, limit: String?, cursor: String?): StepPage {
        val order = sort?.let { StepSort.of(it) ?: throw UiApiException.badRequest("sort must be one of ${StepSort.entries.joinToString { it.param }}") } ?: StepSort.SEQ
        val size = OffsetCursor.limit(limit, DEFAULT_STEPS, MAX_STEPS)
        val offset = OffsetCursor.offset(cursor)
        transcripts.fresh()
        val runId = id.toLongOrNull()?.takeIf { transcripts.queries.run(it) != null } ?: throw UiApiException.notFound("no run $id")
        val page = transcripts.queries.steps(runId, order, offset, size)
        val next = (offset + size).takeIf { it < page.total }?.let(OffsetCursor::of)
        return StepPage(page.items.map(::step), page.total, next)
    }

    private fun item(r: RunRecord) = RunItem(
        id = r.id.toString(), file = r.file, session = r.session, project = r.project, kind = r.kind, role = r.role, ter = r.ter, model = r.model, title = r.title,
        startedAt = iso(r.startMs), endedAt = iso(r.endMs), durationSec = r.durationSec, turns = r.turns, weighted = r.weighted, peakContext = r.peakContext,
        toolResultShare = r.share / 1000.0, toolCalls = r.toolCalls, toolErrors = r.toolErrors,
        overBudget = transcripts.budgets.runWeighted?.let { r.weighted > it } ?: false,
    )

    private fun step(s: StepRecord) = StepItem(
        s.seq, s.turn, s.atMs?.let(::iso), s.tool, s.category, s.summary, s.chars, s.durationMs, s.error, s.errorText, s.carried, s.weighted, s.gap,
    )

    private fun iso(ms: Long) = IsoTime.of(Instant.ofEpochMilli(ms))

    private companion object {
        const val DEFAULT_RUNS = 50
        const val MAX_RUNS = 200
        const val DEFAULT_STEPS = 200
        const val MAX_STEPS = 500
    }
}
