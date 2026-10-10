package codeloupe.commands

import kotlinx.serialization.Serializable

/** The commands of one role or one week: [key] is the role or the ISO week (`2026-W41`), [cost] what its runs cost, [costPct] the share of that the results carried. */
@Serializable
data class CommandSlice(
    val key: String,
    val runs: Int,
    val calls: Int,
    val resultTokens: Long,
    val cost: Long,
    val costPct: Double,
    val groups: List<CommandGroup>,
)
