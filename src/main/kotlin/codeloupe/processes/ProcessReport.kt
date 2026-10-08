package codeloupe.processes

import kotlinx.serialization.Serializable

/** The answer of `GET /processes`: the processes that work in a workspace, and the memory each workspace holds. */
@Serializable
data class ProcessReport(
    val generatedAt: String,
    val workspaces: List<WorkspaceRam> = emptyList(),
    val processes: List<ProcessEntry> = emptyList(),
    /** Why a part is missing: the process table could not be read. */
    val problems: List<String> = emptyList(),
)
