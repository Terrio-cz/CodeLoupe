package codeloupe.workspace

import kotlinx.serialization.Serializable

/** One directory of the registry: a worktree git registered, or a directory under a worktree root it did not. */
@Serializable
data class Workspace(
    val path: String,
    val name: String,
    /** `main`, `worktree`, or `directory` for one git does not know. */
    val role: String,
    val state: WorkspaceState,
    /** Why the state is not plain: what an orphan lacks, a resolved task whose branch is not merged. */
    val note: String? = null,
    val branch: String? = null,
    val head: String? = null,
    val taskId: String? = null,
    val merge: MergeState? = null,
    val tracker: TrackerState? = null,
    /** Latest of the HEAD commit, the last git operation in the worktree and, for an orphan, the directory itself. */
    val lastActivity: String? = null,
    /** Only when asked for: summing a build output directory takes seconds. */
    val sizeBytes: Long? = null,
    /** Only when asked for (`ram=1`): the working set of the processes that work in this directory, and how many they are. */
    val ramBytes: Long? = null,
    val processes: Int? = null,
)
