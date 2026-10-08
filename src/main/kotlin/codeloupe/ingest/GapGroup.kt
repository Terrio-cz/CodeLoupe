package codeloupe.ingest

/** Gaps of one tool and query shape that ended in the same way, [count] of them, the last at [lastMs]. */
data class GapGroup(val tool: String, val shape: String, val fallback: String, val count: Int, val lastMs: Long)
