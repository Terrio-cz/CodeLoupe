package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** One task of the tracker mirror as the Tasks table shows it. */
@Serializable
data class TaskSummary(
    val id: String,
    val project: String,
    val summary: String,
    val state: String,
    val priority: String?,
    val type: String?,
    val assignee: String?,
    val updatedAt: String,
    /** Issue reads served by the mirror; the daemon keeps no counter yet, so 0. */
    val reads: Int,
    val worktreeIds: List<String>,
)
