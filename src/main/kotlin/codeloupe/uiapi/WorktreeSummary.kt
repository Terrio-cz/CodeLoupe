package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** One worktree as the Branches table shows it. */
@Serializable
data class WorktreeSummary(
    val id: String,
    val repoId: String,
    val repoName: String,
    val path: String,
    val branch: String?,
    val head: String,
    val isMain: Boolean,
    val taskId: String?,
    val ahead: Int,
    val behind: Int,
    val changedFiles: Int,
    val changedDecls: Int,
    val layer: LayerState,
    val lastActivityAt: String?,
    /** CodeLoupe calls with this worktree as root in the last 24 hours. */
    val queries24h: Int,
)
