package codeloupe.commands

/**
 * One stored tool call. [inputChars] and [inputHash] describe the whole input of the call (the characters of its names and values, and a hash of them); steps stored before they were kept have
 * none, and the shortened [summary] stands in for both.
 */
data class CommandStep(
    val turn: Int,
    val tool: String,
    val category: String,
    val summary: String,
    val chars: Int,
    val error: Boolean,
    /** The start of the answer, kept for `Write` calls only: it says whether the file was created or replaced. */
    val head: String?,
    val inputChars: Long?,
    val inputHash: Long?,
    /** The group of a shell call as the ingest worked it out; null for steps stored before it did. */
    val command: String?,
)
