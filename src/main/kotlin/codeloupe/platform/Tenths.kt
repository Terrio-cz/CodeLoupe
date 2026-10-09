package codeloupe.platform

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One decimal place the way JavaScript's `toFixed(1)` gives it, which the figures of the workspace script (`run/codemetrics.mjs`)
 * are rounded with: the exact binary value of the number decides, so 1.15 (really 1.149999…) is 1.1 and not 1.2 as `%.1f` or
 * `Math.round(x * 10)` make it.
 */
object Tenths {
    fun of(x: Double): Double = if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toDouble()

    fun text(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toPlainString()
}
