package codeloupe.brain

import kotlinx.serialization.Serializable

/** The places whose change makes the index stale: facts, tasks, the feedback log, workspace.json and the machine-level facts. */
@Serializable
data class BrainPaths(
    val facts: String = "",
    val tasks: String = "",
    val feedback: String = "",
    val config: String = "",
    val machineFacts: String = "",
)
