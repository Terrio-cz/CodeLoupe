package codeloupe.reconcile

import kotlinx.serialization.Serializable

/** A released workspace and how far its cleanup is. */
@Serializable
data class ReleaseStatus(
    val repo: String,
    val workspace: String,
    val at: String,
    /** Resources of it still found at the last look; null before the reconciler has looked. */
    val pending: Int? = null,
    /** Of those, the ones whose removal failed or is blocked and waits for a retry. */
    val retrying: Int? = null,
)
