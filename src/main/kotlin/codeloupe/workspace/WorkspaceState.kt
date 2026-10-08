package codeloupe.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a workspace stands: being worked on, its work is on the default branch, left alone, or not a worktree git knows. */
@Serializable
enum class WorkspaceState {
    @SerialName("active") ACTIVE,
    @SerialName("landed") LANDED,
    @SerialName("abandoned") ABANDONED,
    @SerialName("orphan") ORPHAN,
}
