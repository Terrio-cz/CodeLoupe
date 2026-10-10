package codeloupe.commands

/**
 * One stored tool call. [inputChars] and [inputHash] describe the whole input of the call; steps stored before they were kept have
 * none, and the shortened [summary] stands in for both.
 */
data class CommandStep(
    val turn: Int,
    val tool: String,
    val category: String,
    val summary: String,
    val chars: Int,
    val error: Boolean,
    val inputChars: Long?,
    val inputHash: Long?,
)
