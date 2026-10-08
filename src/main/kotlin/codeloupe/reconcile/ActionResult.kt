package codeloupe.reconcile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How one cleanup attempt ended. */
@Serializable
enum class ActionOutcome {
    /** Removed now. */
    @SerialName("removed") REMOVED,

    /** Was already gone: the wanted state. */
    @SerialName("gone") GONE,

    /** In use or locked; tried again later. */
    @SerialName("blocked") BLOCKED,

    /** Something else went wrong; tried again later. */
    @SerialName("failed") FAILED,

    /** Not attempted: waiting for its backoff, or asked for but not removable. */
    @SerialName("skipped") SKIPPED,
}

/** One attempt, as it is returned to the caller, logged and emitted as an event. */
@Serializable
data class ActionResult(
    val key: String,
    val kind: TargetKind,
    val name: String,
    val workspace: String? = null,
    val outcome: ActionOutcome,
    val detail: String = "",
)
