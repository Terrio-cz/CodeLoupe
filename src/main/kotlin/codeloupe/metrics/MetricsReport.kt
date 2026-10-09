package codeloupe.metrics

import kotlinx.serialization.Serializable

/** The file `codeloupe metrics collect` writes: the figures per role and the summary of every run behind them. */
@Serializable
data class MetricsReport(
    val label: String,
    /** `since` and `until` are absent from reports the workspace script wrote without those options. */
    val since: String? = null,
    val until: String? = null,
    val generated: String,
    val weights: Map<String, Double>,
    val aggregate: Map<String, RoleAggregate>,
    val runs: List<RunSummary>,
) {
    companion object {
        val WEIGHTS = linkedMapOf("input" to 1.0, "cw5m" to 1.25, "cw1h" to 2.0, "cacheRead" to 0.1, "output" to 5.0)
    }
}
