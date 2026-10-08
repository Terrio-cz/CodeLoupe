package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * The Overview screen. Cost comes from the transcript ingest (CL-62); baseline and savings from the baseline report in the
 * daemon's home (CL-133) and are zero or null, with [baseline] saying why, when there is none.
 */
@Serializable
data class Overview(
    val range: String,
    val generatedAt: String,
    val kpis: Kpis,
    val baseline: BaselineInfo,
    val budget: Budget,
    val costSeries: List<CostPoint>,
    val savingsByTool: List<SavedByTool>,
    val toolCalls: List<ToolCalls>,
) {
    @Serializable
    data class Kpis(
        val weightedToday: Long,
        val weightedYesterdaySameTime: Long,
        val weightedRange: Long,
        /** What the runs of the range would have cost at the baseline (runs without a baseline at their real cost); 0 without one. */
        val baselineRange: Long,
        /** [baselineRange] minus [weightedRange]; negative when the runs cost more than the baseline. */
        val savedTokens: Long,
        /** Percent saved on the runs that have a baseline; null when none could be compared. */
        val savedPct: Double?,
        /** Distinct roots that called CodeLoupe in the last 15 minutes: one per working window. */
        val activeWindows: Int,
        val queriedWorktrees: Int,
        val codeloupeCalls: Int,
        val callP50Ms: Long,
        val gaps: Int,
        val newGaps: Int,
    )

    @Serializable
    data class Budget(val dailyWeighted: Long?, val usedToday: Long)

    @Serializable
    data class CostPoint(val t: String, val weighted: Long, val baseline: Long)

    @Serializable
    data class SavedByTool(val tool: String, val calls: Int, val savedTokens: Long)

    /** CodeLoupe's own telemetry of one tool over the range (`calls.jsonl`). */
    @Serializable
    data class ToolCalls(
        val tool: String,
        val calls: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val avgResultChars: Long,
        /** 0..1 */
        val emptyShare: Double,
        val busy: Int,
        val errors: Int,
    )
}
