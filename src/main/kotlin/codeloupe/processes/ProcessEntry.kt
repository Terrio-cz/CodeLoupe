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
    /** `cwd`; `last build` for a Gradle daemon that built there last; `command line` for a process started on a path of the workspace. */
    val via: String,
    /** A Gradle daemon: whether its log says it is busy. */
    val busy: Boolean? = null,
) {
    /** What the reconciler names it by; the start time keeps a reused pid from being mistaken for it. */
    val key: String get() = key(pid, startMs)

    companion object {
        fun key(pid: Long, startMs: Long) = "process:$pid:$startMs"
    }
}
