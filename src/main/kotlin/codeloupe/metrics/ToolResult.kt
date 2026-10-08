package codeloupe.metrics

import kotlinx.serialization.json.JsonObject

/**
 * One tool call with its answer as the transcript shows it. The costs that depend on how many turns followed (carried,
 * attributed) are not here: a run that is still growing changes them.
 */
data class ToolResult(
    val seq: Int,
    val name: String,
    val input: JsonObject,
    val turn: Int,
    /** When the call was made. */
    val atMs: Long?,
    val chars: Int,
    val err: Boolean,
    val ms: Long,
    val errText: String?,
    val head: String,
)
