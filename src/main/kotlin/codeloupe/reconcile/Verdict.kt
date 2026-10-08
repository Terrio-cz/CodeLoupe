package codeloupe.reconcile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the policy says about one resource of a workspace. */
@Serializable
enum class Verdict {
    /** Labelled by CodeLoupe and its workspace has landed: removed without being asked (when `auto` is on). */
    @SerialName("auto") AUTO,

    /** Removable, but only after somebody confirmed it. */
    @SerialName("confirm") CONFIRM,

    /** Stays, with the reason. */
    @SerialName("keep") KEEP,

    /** Named by a `protect` rule of the config: never touched. */
    @SerialName("protected") PROTECTED,
}
