package codeloupe.workspace

import kotlinx.serialization.Serializable

/** The answer of `GET /workspaces`. */
@Serializable
data class WorkspaceList(
    val generatedAt: String,
    val repos: List<RepoWorkspaces>,
    /** Repositories that could not be read, and why. */
    val problems: List<String> = emptyList(),
)
