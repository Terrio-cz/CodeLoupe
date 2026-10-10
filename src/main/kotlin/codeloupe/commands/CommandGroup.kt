package codeloupe.commands

import kotlinx.serialization.Serializable

/**
 * One group of commands with what its calls cost. Tokens are characters over [CommandCost.CHARS_PER_TOKEN]; [argTokens] is the size of
 * what the calls sent. [repeats] counts calls that repeat an identical earlier call of the same run, [repeatTokens] their results.
 * [weighted] is the cost the results carried in context (written once, read on every later turn), [costPct] its share of the cost of
 * the runs counted and [money] the part of what those runs cost in money; null when no model of theirs has a price.
 * [topRuns] are the runs that carried most of it.
 */
@Serializable
data class CommandGroup(
    val command: String,
    val kind: String,
    val calls: Int,
    val runs: Int,
    val resultTokens: Long,
    val argTokens: Long,
    val repeats: Int,
    val repeatTokens: Long,
    val errors: Int,
    val weighted: Long,
    val costPct: Double,
    val money: Double? = null,
    val topRuns: List<CommandRunRef> = emptyList(),
)
