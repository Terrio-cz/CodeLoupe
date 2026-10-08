package codeloupe.metrics

/** A [Gap] with where it happened: the call ([seq], [turn]) and, for a fallback, what the agent reached for instead. */
data class LocatedGap(val gap: Gap, val seq: Int, val turn: Int, val fallback: String?)
