package codeloupe.metrics

/**
 * A usage-limit answer Claude Code wrote into a transcript ("You've hit your weekly limit · resets Oct 11, 8pm"): when it was
 * written, when the limit lifts if the line says, and which limit it was (`session`, `weekly`, a model name, `spend`, or `limit`).
 */
data class LimitHit(val atMs: Long, val resetsAtMs: Long?, val kind: String, val text: String)
