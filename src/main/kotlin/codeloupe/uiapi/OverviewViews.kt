package codeloupe.uiapi

import codeloupe.daemon.CallRecord
import java.time.Instant

/**
 * The Overview screen from CodeLoupe's own call telemetry. Cost, baseline and savings need the transcript ingest of
 * CL-62 and are zero until then; the gap count likewise.
 */
internal class OverviewViews(private val calls: CallLog, private val worktrees: WorktreeViews) {
    suspend fun overview(range: String, now: Instant = Instant.now()): Overview {
        val days = DAYS[range] ?: throw UiApiException.badRequest("range must be one of ${DAYS.keys.joinToString()}")
        val records = calls.after(now.minusSeconds(days * 86_400))
        val roots = records.mapNotNull { it.root }.distinct()
        val recent = records.filter { instant(it)?.isAfter(now.minusSeconds(ACTIVE_WINDOW_S)) == true }.mapNotNull { it.root }.distinct()
        val known = worktrees.all()
        val queried = known.count { w -> roots.any { WorktreeId.contains(w.path, it) } }
        return Overview(
            range = range, generatedAt = now.toString(),
            kpis = Overview.Kpis(0, 0, 0, 0, 0, 0.0, recent.size, queried, records.size, percentile(records.map { it.ms }, 50), 0, 0),
            budget = Overview.Budget(null, 0), costSeries = emptyList(), savingsByTool = emptyList(),
            toolCalls = records.groupBy { it.tool }.map { (tool, rs) ->
                Overview.ToolCalls(
                    tool, rs.size, percentile(rs.map { it.ms }, 50), percentile(rs.map { it.ms }, 95), rs.sumOf { it.chars.toLong() } / rs.size,
                    rs.count { it.empty }.toDouble() / rs.size, rs.count { it.busy }, rs.count { !it.ok && !it.busy },
                )
            }.sortedByDescending { it.calls },
        )
    }

    private fun instant(r: CallRecord) = runCatching { Instant.parse(r.t) }.getOrNull()

    /** Nearest-rank percentile; 0 for no values. */
    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
    }

    private companion object {
        val DAYS = linkedMapOf("24h" to 1L, "7d" to 7L, "30d" to 30L)
        const val ACTIVE_WINDOW_S = 15 * 60L
    }
}
