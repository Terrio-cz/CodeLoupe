package codeloupe.daemon

import codeloupe.config.BudgetsConfig
import kotlinx.serialization.Serializable

/** Latency and shape of the most recent calls: [window] calls, percentiles in ms and chars, rates 0..1. */
@Serializable
data class CallLatency(
    val window: Int = 0,
    val p50Ms: Long = 0,
    val p95Ms: Long = 0,
    val p95Chars: Int = 0,
    val emptyRate: Double = 0.0,
    val busyRate: Double = 0.0,
    val byTool: Map<String, ToolLatency> = emptyMap(),
) {
    /** The same figures with each tool's p95 budget, so a reader of `/status` judges every tool against its own. */
    fun withBudgets(budgets: BudgetsConfig) = copy(byTool = byTool.mapValues { (tool, t) -> t.copy(budgetMs = budgets.p95For(tool)) })
}
