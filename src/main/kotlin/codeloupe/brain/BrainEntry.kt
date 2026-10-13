package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * One entry of a workspace brain as `brain.mjs export` prints it: a fact entry of the workspace or machine level, or a finding line of
 * a task (`level` = `task`, no id). Whole text always; the daemon never shortens an entry.
 */
@Serializable
data class BrainEntry(
    val where: String,
    val text: String,
    val level: String = "workspace",
    val topic: String = "",
    val line: Int = 0,
    val id: String? = null,
    val kind: String = "fact",
    val task: String? = null,
    val at: String? = null,
    val who: String? = null,
    val marker: String = "",
    val files: List<String> = emptyList(),
    val symbols: List<String> = emptyList(),
    val anchors: List<String> = emptyList(),
    val supersedes: List<String> = emptyList(),
    val superseded: Boolean = false,
    /** The automated check that landed for what this entry says (CL-235): the entry is then `superseded` and left out of briefs and risk. */
    val coveredBy: String? = null,
    /** Set on a ranked hit: the sum of the signals below. */
    val score: Int? = null,
    /** Set on a ranked hit: what tied it to the question (`file`, `symbol`, `caller`, `task`, `module`, `text`, `feedback`). */
    val why: List<String> = emptyList(),
) {
    val isTaskLine: Boolean get() = level == TASK_LEVEL

    fun under(scope: String): Boolean = topic == scope || topic.startsWith("$scope/")

    companion object {
        const val TASK_LEVEL = "task"
    }
}
