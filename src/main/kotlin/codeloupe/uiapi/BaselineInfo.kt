package codeloupe.uiapi

import codeloupe.metrics.BaselineStore
import kotlinx.serialization.Serializable

/** Which baseline the savings are measured against, or why there is none; never a made-up figure. */
@Serializable
data class BaselineInfo(
    /** `ok`, `none` (no `baseline.json` in the daemon's home) or `unreadable`. */
    val state: String,
    val label: String? = null,
    val since: String? = null,
    val until: String? = null,
    /** Runs the baseline was taken from. */
    val runs: Int = 0,
    /** Share (0..1) of the cost in the range that belongs to runs with a baseline for their role: only those are compared. Null without a baseline. */
    val coveredShare: Double? = null,
    val message: String? = null,
) {
    companion object {
        fun of(state: BaselineStore.State, coveredShare: Double? = null): BaselineInfo = when (state) {
            BaselineStore.State.Missing -> BaselineInfo("none", message = "no baseline: run `codeloupe metrics collect --baseline --since <day>` over the period before CodeLoupe")
            is BaselineStore.State.Unreadable -> BaselineInfo("unreadable", message = state.message)
            is BaselineStore.State.Loaded -> state.baseline.let { BaselineInfo("ok", it.label, it.since, it.until, it.runs, coveredShare) }
        }
    }
}
