package codeloupe.workspace

import kotlinx.serialization.Serializable

/** A workspace's HEAD against the repository's default branch. */
@Serializable
data class MergeState(
    val defaultRef: String,
    /** Commits the workspace has that the default branch lacks (counted up to 1000). */
    val ahead: Int,
    /** Everything the workspace committed is on the default branch. */
    val merged: Boolean,
    /** Subject of the HEAD commit. */
    val subject: String?,
)
