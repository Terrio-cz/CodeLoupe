package codeloupe.workspace

import kotlinx.serialization.Serializable

/** A workspace by the names its Docker labels use: the repository (its main worktree's name) and the worktree's name. */
@Serializable
data class WorkspaceRef(val repo: String, val workspace: String)
