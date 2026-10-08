package codeloupe.processes

import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.Serializable

/** What the processes of one workspace hold in memory: all of them, and the build tools among them. */
@Serializable
data class WorkspaceRam(
    val repo: String,
    val workspace: String,
    val state: WorkspaceState,
    val processes: Int,
    val rssMb: Long,
    val buildRssMb: Long,
)
