package codeloupe.commands

import codeloupe.metrics.Usage
import kotlin.math.floor

/**
 * What a tool result costs while it stays in the context, as `metrics collect` counts it: a quarter token per character, written once to
 * the cache and read again on every later turn of the run. The run's own cost has the corrected output rule of the parser (CL-206).
 */
object CommandCost {
    const val CHARS_PER_TOKEN = 4.0

    fun tokens(chars: Long): Long = Math.round(chars / CHARS_PER_TOKEN)

    /** Weighted tokens of a result of [chars] characters that [turnsAfter] more turns of the run followed. */
    fun carried(chars: Int, turnsAfter: Int): Long = floor(chars / CHARS_PER_TOKEN * (Usage.WRITE_1H + Usage.READ * maxOf(0, turnsAfter)) + 0.5).toLong()
}
