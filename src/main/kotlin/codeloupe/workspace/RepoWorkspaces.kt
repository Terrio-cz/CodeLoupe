package codeloupe.workspace

import kotlinx.serialization.Serializable

/** The workspaces of one repository. */
@Serializable
data class RepoWorkspaces(
    /** The main worktree. */
    val repo: String,
    val name: String,
    val commonDir: String,
    val defaultRef: String,
    /** Directories scanned for worktrees git does not know. */
    val roots: List<String>,
    /** Workspaces per state name. */
    val counts: Map<String, Int>,
    val workspaces: List<Workspace>,
)
