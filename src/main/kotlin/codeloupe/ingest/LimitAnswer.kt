package codeloupe.ingest

import kotlinx.serialization.Serializable

/**
 * A usage-limit answer a run got (CL-210), as `metrics limits --hits` exports it: when, which limit ([kind] `session` or `weekly`), when it
 * lifts, the session and its account (null when not decidable). The text of the answer is not exported.
 */
@Serializable
data class LimitAnswer(val at: String, val kind: String, val resetsAt: String?, val session: String, val account: String?)
