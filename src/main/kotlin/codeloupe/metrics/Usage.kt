package codeloupe.metrics

import kotlinx.serialization.Serializable
import kotlin.math.floor

/** Token counts of a run by price class; [cost] weighs them by the relative prices all current models share. */
@Serializable
data class Usage(
    val input: Long = 0,
    val cw5m: Long = 0,
    val cw1h: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
) {
    operator fun plus(other: Usage) = Usage(
        input + other.input, cw5m + other.cw5m, cw1h + other.cw1h, cacheRead + other.cacheRead, output + other.output,
    )

    /** Relative price units: input 1, 5 min cache write 1.25, 1 h write 2, cache read 0.1, output 5. */
    fun cost(): Long = floor(weighted() + 0.5).toLong()

    /** [cost] before rounding, for sums over parts of a run. */
    fun weighted(): Double = input * 1.0 + cw5m * 1.25 + cw1h * 2.0 + cacheRead * 0.1 + output * 5.0

    companion object {
        /** What an hour-cached result costs once written, per later turn it is read again. */
        const val WRITE_1H = 2.0
        const val READ = 0.1
    }
}
