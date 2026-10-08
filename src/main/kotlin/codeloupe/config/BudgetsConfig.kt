package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the daemon may cost before `/status` warns, from `config.json` `budgets`:
 * `p95Ms` latency of the slowest 5 % of recent calls, `queueWaitMs` the longest a job waited for its lane, `rssMb`
 * resident memory, `busyRate` the share of recent calls answered "busy".
 */
data class BudgetsConfig(
    val p95Ms: Long = 1_000,
    val queueWaitMs: Long = 30_000,
    val rssMb: Long = 250,
    val busyRate: Double = 0.1,
) {
    companion object {
        fun parse(file: JsonObject): BudgetsConfig {
            val budgets = file["budgets"] as? JsonObject ?: return BudgetsConfig()
            fun number(key: String) = (budgets[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }
            val default = BudgetsConfig()
            return BudgetsConfig(
                p95Ms = number("p95Ms")?.toLong() ?: default.p95Ms,
                queueWaitMs = number("queueWaitMs")?.toLong() ?: default.queueWaitMs,
                rssMb = number("rssMb")?.toLong() ?: default.rssMb,
                busyRate = number("busyRate") ?: default.busyRate,
            )
        }
    }
}
