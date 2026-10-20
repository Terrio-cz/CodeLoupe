package codeloupe.daemon

import codeloupe.config.BudgetsConfig
import kotlinx.serialization.Serializable

/** Whether the daemon is within its budgets; [warnings] name each one that is not, with the value and the limit. */
@Serializable
data class BudgetState(val ok: Boolean, val warnings: List<String> = emptyList()) {
    companion object {
        // Percentiles of a handful of calls say little: latency is judged once the window, and a tool, holds this many.
        private const val MIN_CALLS = 20

        fun check(budgets: BudgetsConfig, latency: CallLatency, rssMb: Long?, queueWaitMs: Long): BudgetState {
            val warnings = buildList {
                // Each tool against its own budget: one p95 over all tools let composite tools drown out the lookups.
                latency.byTool.forEach { (tool, t) ->
                    val limit = budgets.p95For(tool)
                    if (t.calls >= MIN_CALLS && t.p95Ms > limit) add("$tool p95 latency ${t.p95Ms} ms exceeds $limit ms")
                }
                if (latency.window >= MIN_CALLS && latency.busyRate > budgets.busyRate) add("busy rate ${latency.busyRate} exceeds ${budgets.busyRate}")
                if (queueWaitMs > budgets.queueWaitMs) add("queue wait $queueWaitMs ms exceeds ${budgets.queueWaitMs} ms")
                if (rssMb != null && rssMb > budgets.rssMb) add("rss $rssMb MB exceeds ${budgets.rssMb} MB")
            }
            return BudgetState(warnings.isEmpty(), warnings)
        }
    }
}
