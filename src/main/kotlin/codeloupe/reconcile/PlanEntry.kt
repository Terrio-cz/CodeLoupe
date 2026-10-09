package codeloupe.reconcile

import codeloupe.docker.OwnershipClass
import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.Serializable

/** One resource (or orphan directory) of a workspace, with what the policy decides and how its cleanup is going. */
@Serializable
data class PlanEntry(
    /** `<kind>:<id>` (volumes by name, directories by path): what `confirm` names. */
    val key: String,
    val kind: TargetKind,
    val name: String,
    val repo: String? = null,
    val workspace: String? = null,
    /** `owned`, `adopted`, or null for an orphan directory (no Docker owner). */
    val ownership: OwnershipClass? = null,
    val workspaceState: WorkspaceState? = null,
    val verdict: Verdict,
    val reason: String,
    /** The workspace was released (`ws release`): this entry goes without asking, whatever state the workspace is in. */
    val released: Boolean = false,
    /** Failed or blocked attempts so far; the next one waits until [nextAttempt]. */
    val attempts: Int = 0,
    val nextAttempt: String? = null,
    val lastError: String? = null,
    /** A process: the directory of the workspace it was found in, which it is checked against again before it is stopped. */
    val path: String? = null,
    /** A container: it was running (or restarting, paused) when planned; one that started since is not stopped without a new plan. */
    val running: Boolean = false,
)
