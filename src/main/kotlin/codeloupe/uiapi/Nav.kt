package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The counts the desktop app's sidebar shows. */
@Serializable
data class Nav(val activeWorktrees: Int, val openTasks: Int, val newGaps: Int, val indexState: RepoIndexState)
