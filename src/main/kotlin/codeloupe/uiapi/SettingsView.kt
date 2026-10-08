package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The daemon's effective configuration, without secrets. */
@Serializable
data class SettingsView(
    val port: Int,
    val home: String,
    val configFile: String,
    val defaultRoot: String?,
    val repos: List<Repo>,
    val youtrack: List<Youtrack>,
    val budgets: Budgets,
) {
    @Serializable
    data class Repo(val id: String, val path: String, val baseRef: String)

    @Serializable
    data class Youtrack(val url: String, val projects: List<String>, val tokenConfigured: Boolean, val pollSec: Long)

    @Serializable
    data class Budgets(
        val dailyWeighted: Long?,
        val daemonRssMb: Long,
        val buildPeakRssMb: Long,
        /** The `config.json` `budgets` that `/status` judges the daemon by: p95 latency, queue wait and busy rate. */
        val p95Ms: Long,
        val queueWaitMs: Long,
        val busyRate: Double,
    )
}
