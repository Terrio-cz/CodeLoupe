package codeloupe.hooks

import kotlinx.serialization.Serializable

/** One line of `hooks.jsonl`: a hook that spoke. Names and counts only, never the command or the file text. */
@Serializable
data class HookRecord(
    val t: String,
    val hook: String,
    val tool: String,
    /** `advised`, `denied`, or `context` for a session start. */
    val decision: String,
    /** The kind of advice (`search:usages`, `read`, …). */
    val why: String,
    /** The CodeLoupe tool that was suggested. */
    val suggested: String,
    val session: String,
    val root: String? = null,
    val ms: Double = 0.0,
    /** Size of the context a session start added, in tokens (3.2 characters each); 0 for the other hooks. */
    val tokens: Int = 0,
)
