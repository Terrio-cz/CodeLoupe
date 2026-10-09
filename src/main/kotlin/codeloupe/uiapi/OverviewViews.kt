package codeloupe.uiapi

import codeloupe.daemon.CallRecord
import codeloupe.ingest.RunWriter
import codeloupe.ingest.Transcripts
import codeloupe.metrics.BaselineStore
import codeloupe.platform.NearestRank
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The Overview screen: CodeLoupe's own call telemetry, and the cost, budget and gap figures of the transcript ingest. Baseline
 * and savings come from `<home>/baseline.json` ([Savings]); without one they are zero and the answer says why.
 */
internal class OverviewViews(
    private val calls: CallLog,
    private val worktrees: WorktreeViews,
    private val transcripts: Transcripts,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun overview(range: String, account: AccountFilter? = null, now: Instant = Instant.now()): Overview {
        val days = Ranges.days(range)
        transcripts.fresh()
        val records = calls.after(now.minusSeconds(days * 86_400)).filter { account == null || it.root?.let(account.owns) == true }
        val roots = records.mapNotNull { it.root }.distinct()
        val recent = records.filter { instant(it)?.isAfter(now.minusSeconds(ACTIVE_WINDOW_S)) == true }.mapNotNull { it.root }.distinct()
        val known = worktrees.all()
        val queried = known.count { w -> roots.any { WorktreeId.contains(w.path, it) } }
        val today = now.atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant()
        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant()
        val lastHour = Math.floorDiv(now.toEpochMilli(), RunWriter.HOUR_MS)
        val firstHour = CostWindows.firstHour(now, maxOf(days, 2))
        val baseline = transcripts.baseline.state()
        val savings = (baseline as? BaselineStore.State.Loaded)?.let {
            Savings(transcripts.queries.usageRows(firstHour, lastHour + 1, account?.transcriptPrefix), it.baseline, now.minusSeconds(ACTIVE_WINDOW_S).toEpochMilli())
        }
        val windows = CostWindows(transcripts.queries.hours(firstHour, lastHour + 1, account?.transcriptPrefix), zone, savings?.baselineHours().orEmpty())
        val series = if (days == 1L) windows.hourly(HOURS_PER_DAY, now) else windows.daily(days.toInt(), today)
        val usedToday = windows.between(todayStart.toEpochMilli(), Long.MAX_VALUE)
        val weightedRange = series.sumOf { it.weighted }
        val compared = savings?.from(Math.floorDiv(Instant.parse(series.first().t).toEpochMilli(), RunWriter.HOUR_MS))
        val baselineRange = if (compared?.savedPct == null) 0L else series.sumOf { it.baseline }
        val sameTimeYesterday = yesterdayStart.plus(Duration.between(todayStart, now))
        return Overview(
            range = range, generatedAt = now.toString(),
            kpis = Overview.Kpis(
                usedToday, windows.between(yesterdayStart.toEpochMilli(), sameTimeYesterday.toEpochMilli()), weightedRange, baselineRange, if (baselineRange == 0L) 0 else baselineRange - weightedRange, compared?.savedPct, recent.size, queried,
                records.size, percentile(records.map { it.ms }, 50), transcripts.queries.gapCount(now.minusSeconds(days * 86_400).toEpochMilli(), account?.transcriptPrefix),
                transcripts.queries.gapCount(now.minusSeconds(NEW_GAP_S).toEpochMilli(), account?.transcriptPrefix),
            ),
            baseline = BaselineInfo.of(baseline, compared?.coveredShare), budget = Overview.Budget(transcripts.budgets.dailyWeighted, usedToday),
            costSeries = if (compared?.savedPct == null) series.map { it.copy(baseline = 0) } else series, savingsByTool = emptyList(),
            toolCalls = records.groupBy { it.tool }.map { (tool, rs) ->
                Overview.ToolCalls(
                    tool, rs.size, percentile(rs.map { it.ms }, 50), percentile(rs.map { it.ms }, 95), rs.sumOf { it.chars.toLong() } / rs.size,
                    rs.count { it.empty }.toDouble() / rs.size, rs.count { it.busy }, rs.count { !it.ok && !it.busy },
                )
            }.sortedByDescending { it.calls },
        )
    }

    private fun instant(r: CallRecord) = runCatching { Instant.parse(r.t) }.getOrNull()

    private fun percentile(values: List<Long>, p: Int): Long = NearestRank.of(values, p / 100.0)

    private companion object {
        const val ACTIVE_WINDOW_S = 15 * 60L
        const val HOURS_PER_DAY = 24
        const val NEW_GAP_S = 86_400L
    }
}
