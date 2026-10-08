package codeloupe.metrics

import kotlinx.serialization.Serializable

/** Median, upper quartile and total of one figure over the runs of a role. */
@Serializable
data class Pick(val median: Long, val p75: Long, val sum: Long) {
    companion object {
        fun of(values: List<Long>): Pick {
            if (values.isEmpty()) return Pick(0, 0, 0)
            val s = values.sorted()
            val m = s.size shr 1
            val median = if (s.size % 2 == 1) s[m] else Math.round((s[m - 1] + s[m]) / 2.0)
            return Pick(median, s[minOf(s.size - 1, s.size * 3 / 4)], s.sum())
        }
    }
}
