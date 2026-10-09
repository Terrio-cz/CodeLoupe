package codeloupe.platform

import java.math.BigDecimal
import java.math.RoundingMode

/** Two decimal places the way JavaScript's `toFixed(2)` gives them, as [Tenths] does for one: the exact binary value decides. */
object Hundredths {
    fun of(x: Double): Double = if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(2, RoundingMode.HALF_UP).toDouble()
}
