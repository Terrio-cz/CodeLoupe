package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * One tool call of a run. [summary] says what it was about (command, file, pattern), masked and at most 200 characters, never
 * the content. [carried] is result characters times the turns that followed, [weighted] what keeping the result in context cost,
 * [gap] the kind of gap a CodeLoupe call ended in.
 */
@Serializable
data class StepItem(
    val seq: Int,
    val turn: Int,
    val at: String?,
    val tool: String,
    val category: String,
    val summary: String,
    val chars: Int,
    val durationMs: Long,
    val error: Boolean,
    val errorText: String?,
    val carried: Long,
    val weighted: Long,
    val gap: String?,
)
