package codeloupe.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Where a [TranscriptParser] stands after some lines, so a longer transcript can be read from the last offset on. */
@Serializable
data class ParserSnapshot(
    val role: String,
    val start: String?,
    val end: String?,
    val model: String?,
    val turns: Int,
    val usage: Usage,
    val peak: Long,
    val firstPrompt: String,
    val emitted: Int,
    /** Hashes of the assistant message ids counted so far: one message appears on several lines. */
    val seen: List<Long>,
    val pending: List<Call>,
    /** Absent from snapshots written before starting contexts were measured. */
    val startCtx: StartTracker.State? = null,
) {
    /** A tool call whose result has not been read yet; [input] keeps plain values only. */
    @Serializable
    data class Call(val id: String, val name: String, val input: JsonObject, val atMs: Long?, val turn: Int)
}
