package codeloupe.hooks

import kotlinx.serialization.Serializable

/** `/status` `hooks`: what the hook endpoint did since the daemon started, by outcome, and how long it took. */
@Serializable
data class HookStats(
    val calls: Long = 0,
    val advised: Long = 0,
    val denied: Long = 0,
    /** Sessions that started with a map and the state of their worktree. */
    val sessions: Long = 0,
    /** Sessions told that they carry too much. */
    val warnings: Long = 0,
    /** Calls left alone, by the reason: `off`, `ignored`, `no-context`, `below-size`, `not-a-search`, `other-files`, `not-indexed`, `small`, `repeat`, `capped`, `error`. */
    val passed: Map<String, Long> = emptyMap(),
    val medianMs: Double = 0.0,
    val p95Ms: Double = 0.0,
)
