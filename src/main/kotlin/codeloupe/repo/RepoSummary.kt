package codeloupe.repo

import kotlinx.serialization.Serializable

/** One repository line of `/status`. */
@Serializable
data class RepoSummary(
    val id: String,
    val commonDir: String,
    val defaultRef: String,
    val baseCommit: String?,
    val lastBuild: LastBuild?,
    val failure: BuildFailure?,
    /** Worktrees with an overlay of their own changes. */
    val overlays: Int = 0,
)
