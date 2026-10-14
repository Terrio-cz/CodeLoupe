package codeloupe.ingest

import kotlinx.serialization.Serializable

/**
 * The tokens one run spent with one model within one minute (CL-226), by price class; [thinking] is the part of [output] that was thinking.
 * [minute] is the start of the minute, [session], [kind] (`session` or `subagent`) and [role] say which run, [account] and [identity] whose
 * login it ran on (null when not decidable). Numbers and names only, nothing of the conversation.
 */
@Serializable
data class TokenMinute(
    val minute: String,
    val session: String,
    val kind: String,
    val role: String,
    val project: String,
    val model: String,
    val input: Long,
    val cw5m: Long,
    val cw1h: Long,
    val cacheRead: Long,
    val output: Long,
    val thinking: Long,
    val account: String?,
    val identity: String?,
)
