package codeloupe.daemon

import kotlinx.serialization.Serializable

/** One line of `calls.jsonl`: which tool, how long, how big — never the content. */
@Serializable
data class CallRecord(
    val t: String,
    val tool: String,
    val via: String,
    val ms: Long,
    val chars: Int,
    val ok: Boolean,
    val busy: Boolean,
    val empty: Boolean,
)
