package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the daemon may cost before `/status` warns, from `config.json` `budgets`:
 * `p95Ms` latency of the slowest 5 % of a tool's recent calls, `toolP95Ms` the same per tool (composite tools that read git,
 * the tracker or the brain have their own defaults), `queueWaitMs` the longest a job waited for its lane, `rssMb`
 * resident memory, `busyRate` the share of recent calls answered "busy". `dailyWeighted` and `runWeighted` are the weighted
 * tokens (docs/ui-spec.md § 9.1) a day and a single agent run may use before the desktop app is told; unset = no limit.
 */
data class BudgetsConfig(
    val p95Ms: Long = 1_000,
    val toolP95Ms: Map<String, Long> = DEFAULT_TOOL_P95_MS,
    val queueWaitMs: Long = 30_000,
    val rssMb: Long = 250,
    val busyRate: Double = 0.1,
    val dailyWeighted: Long? = null,
    val runWeighted: Long? = null,
) {
    /** The p95 budget of [tool]: its own one, else the lookup default [p95Ms]. */
    fun p95For(tool: String): Long = toolP95Ms[tool] ?: p95Ms

    companion object {
        /** Measured p95 of the composite tools on the terrio workspace (CL-257) with headroom; lookups stay at [p95Ms]. */
        val DEFAULT_TOOL_P95_MS = mapOf(
            "brain_brief" to 3_000L,
            "changes" to 5_000L,
            "dispatch_plan" to 10_000L,
            "task_code" to 3_000L,
            "task_context" to 3_000L,
        )

        fun parse(file: JsonObject): BudgetsConfig {
            val budgets = file["budgets"] as? JsonObject ?: return BudgetsConfig()
            fun positive(value: Any?) = (value as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }
            fun number(key: String) = positive(budgets[key])
            val default = BudgetsConfig()
            val tools = (budgets["toolP95Ms"] as? JsonObject)?.mapNotNull { (tool, ms) -> positive(ms)?.let { tool to it.toLong() } }?.toMap().orEmpty()
            return BudgetsConfig(
                p95Ms = number("p95Ms")?.toLong() ?: default.p95Ms,
                toolP95Ms = DEFAULT_TOOL_P95_MS + tools,
                queueWaitMs = number("queueWaitMs")?.toLong() ?: default.queueWaitMs,
                rssMb = number("rssMb")?.toLong() ?: default.rssMb,
                busyRate = number("busyRate") ?: default.busyRate,
                dailyWeighted = number("dailyWeighted")?.toLong(),
                runWeighted = number("runWeighted")?.toLong(),
            )
        }
    }
}
