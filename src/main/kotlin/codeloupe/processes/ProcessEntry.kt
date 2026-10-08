package codeloupe.processes

import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.Serializable

/** A process that belongs to a workspace: it works in its directory (or is started on a path inside it). */
@Serializable
data class ProcessEntry(
    val pid: Long,
    val startMs: Long,
    val kind: ProcessKind,
    val name: String,
    /** Masked of secrets and cut. */
    val commandLine: String,
    val cwd: String?,
    val rssMb: Long,
    val repo: String,
    val workspace: String,
    val workspaceState: WorkspaceState,
    /** The directory of the workspace, as the registry names it. */
    val path: String,
    /** `cwd`, or `command line` when the process works elsewhere but was started on a path of the workspace. */
    val via: String,
) {
    /** What the reconciler names it by; the start time keeps a reused pid from being mistaken for it. */
    val key: String get() = key(pid, startMs)

    companion object {
        fun key(pid: Long, startMs: Long) = "process:$pid:$startMs"
    }
}
