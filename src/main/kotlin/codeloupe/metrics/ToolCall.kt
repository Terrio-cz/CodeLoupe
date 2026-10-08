package codeloupe.metrics

import kotlinx.serialization.json.JsonObject

/**
 * One tool call of a run with its result. Sizes are characters of the result; [carried] is chars × the assistant turns
 * that followed in the same run, [attr] the relative cost of keeping the result in context.
 */
data class ToolCall(
    val seq: Int,
    val name: String,
    val category: String,
    val input: JsonObject,
    val file: String?,
    val partial: Boolean,
    val cmd: String?,
    val turn: Int,
    val chars: Int,
    val err: Boolean,
    val ms: Long,
    val errText: String?,
    /** The start of the result text, enough to tell an empty or busy answer. */
    val head: String,
    val carried: Long,
    val attr: Long,
)
