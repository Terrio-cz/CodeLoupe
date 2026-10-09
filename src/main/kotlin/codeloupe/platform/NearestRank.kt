package codeloupe.platform

import kotlin.math.ceil

/** The nearest-rank percentile: the smallest value that at least a fraction [of] the values do not exceed. */
object NearestRank {
    /** [fraction] is 0.5 for the median, 0.95 for the 95th percentile; 0 for no values. */
    fun of(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
    }
}
