package codeloupe.daemon

import kotlinx.serialization.Serializable

/** One tool's share of the recent calls; [budgetMs] is its p95 budget, set by `/status` ([CallLatency.withBudgets]). */
@Serializable
data class ToolLatency(val calls: Int, val p50Ms: Long, val p95Ms: Long, val budgetMs: Long? = null)
