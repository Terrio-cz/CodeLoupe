package codeloupe.metrics

import kotlinx.serialization.Serializable

/** Calls of one category in a run (or several): how many, how big, how long they stay in context, what they cost. */
@Serializable
data class CatStats(
    val calls: Int = 0,
    val chars: Long = 0,
    val carried: Long = 0,
    val attr: Long = 0,
    val errors: Int = 0,
    val ms: Long = 0,
) {
    operator fun plus(other: CatStats) = CatStats(
        calls + other.calls, chars + other.chars, carried + other.carried, attr + other.attr, errors + other.errors, ms + other.ms,
    )
}
