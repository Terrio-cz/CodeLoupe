package codeloupe.hooks

import kotlinx.serialization.Serializable

/** One line of `hooks.jsonl`: a hook that spoke. Names and counts only, never the command or the file text. */
@Serializable
data class HookRecord(
    val t: String,
    val hook: String,
    val tool: String,
    /** `advised`, `denied`, `context` for a session start, `warned` for a session that carries too much. */
    val decision: String,
    /** The kind of advice (`search:usages`, `read`, …). */
    val why: String,
    /** The CodeLoupe tool that was suggested. */
    val suggested: String,
    val session: String,
    val root: String? = null,
    val ms: Double = 0.0,
    /** Tokens: what a session start added (3.2 characters each), or the context a warned session carried; 0 for the other hooks. */
    val tokens: Int = 0,
)
