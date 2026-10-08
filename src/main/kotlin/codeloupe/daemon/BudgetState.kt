package codeloupe.daemon

import codeloupe.config.BudgetsConfig
import kotlinx.serialization.Serializable

/** Whether the daemon is within its budgets; [warnings] name each one that is not, with the value and the limit. */
@Serializable
data class BudgetState(val ok: Boolean, val warnings: List<String> = emptyList()) {
    companion object {
        // Percentiles of a handful of calls say little: latency is judged once the window holds this many.
        private const val MIN_CALLS = 20

        fun check(budgets: BudgetsConfig, latency: CallLatency, rssMb: Long?, queueWaitMs: Long): BudgetState {
            val warnings = buildList {
                if (latency.window >= MIN_CALLS && latency.p95Ms > budgets.p95Ms) add("p95 latency ${latency.p95Ms} ms exceeds ${budgets.p95Ms} ms")
                if (latency.window >= MIN_CALLS && latency.busyRate > budgets.busyRate) add("busy rate ${latency.busyRate} exceeds ${budgets.busyRate}")
                if (queueWaitMs > budgets.queueWaitMs) add("queue wait $queueWaitMs ms exceeds ${budgets.queueWaitMs} ms")
                if (rssMb != null && rssMb > budgets.rssMb) add("rss $rssMb MB exceeds ${budgets.rssMb} MB")
            }
            return BudgetState(warnings.isEmpty(), warnings)
        }
    }
}
