package codeloupe.commands

import kotlinx.serialization.Serializable

/** What one tool category (as `metrics collect` sorts the calls) carried: the figures of `collect`, from the ingested steps. */
@Serializable
data class CommandCategory(val category: String, val calls: Int, val resultTokens: Long, val errors: Int, val weighted: Long, val costPct: Double)
