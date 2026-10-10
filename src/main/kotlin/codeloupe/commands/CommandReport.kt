package codeloupe.commands

import kotlinx.serialization.Serializable

/**
 * Which commands the agents of a period used and what they cost (`codeloupe metrics commands`, the Commands tab of the Runs screen).
 * [cost] is the weighted cost of the [runs] counted, [weighted] the part of it that the results of their [calls] carried
 * ([costPct]); [money] is that cost in [currency] at the price table of `metrics collect`, and [unpricedRuns] the runs of a model
 * the table does not know, which add nothing to it. [groups] are the [groupCount] groups of all roles, [roles] and [weeks] the same
 * by role and by week (the weeks of the runs' starts), [categories] the tool categories of `collect` for comparison.
 */
@Serializable
data class CommandReport(
    val generatedAt: String,
    val since: String?,
    val until: String?,
    val role: String?,
    val runs: Int,
    val calls: Int,
    val resultTokens: Long,
    val argTokens: Long,
    val cost: Long,
    val weighted: Long,
    val costPct: Double,
    val money: Double?,
    val currency: String,
    val unpricedRuns: Int,
    val groupCount: Int,
    val groups: List<CommandGroup>,
    val categories: List<CommandCategory>,
    val roles: List<CommandSlice>,
    val weeks: List<CommandSlice>,
)
