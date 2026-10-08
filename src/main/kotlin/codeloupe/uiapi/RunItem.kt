package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** One agent run in the list: its cost in weighted tokens, how much of it tool results carry (0..1) and whether it went over `budgets.runWeighted`. */
@Serializable
data class RunItem(
    val id: String,
    val file: String,
    val session: String,
    val project: String,
    /** `session` or `subagent`. */
    val kind: String,
    val role: String,
    val ter: String?,
    val model: String?,
    /** The first line of the prompt, masked and cut. */
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val durationSec: Long,
    val turns: Int,
    val weighted: Long,
    val peakContext: Long,
    val toolResultShare: Double,
    val toolCalls: Int,
    val toolErrors: Int,
    val overBudget: Boolean,
)
