package codeloupe.daemon

import kotlin.math.ceil

/** The last [SIZE] calls, kept to answer percentiles for `/status`; older ones live in `calls.jsonl`. */
class CallWindow {
    private class Entry(val tool: String, val ms: Long, val chars: Int, val busy: Boolean, val empty: Boolean)

    private val ring = arrayOfNulls<Entry>(SIZE)
    private var next = 0
    private var filled = 0

    @Synchronized
    fun record(tool: String, ms: Long, chars: Int, busy: Boolean, empty: Boolean) {
        ring[next] = Entry(tool, ms, chars, busy, empty)
        next = (next + 1) % SIZE
        if (filled < SIZE) filled++
    }

    fun snapshot(): CallLatency {
        val entries = synchronized(this) { List(filled) { ring[it]!! } }
        if (entries.isEmpty()) return CallLatency()
        return CallLatency(
            window = entries.size,
            p50Ms = percentile(entries.map { it.ms }, 0.5),
            p95Ms = percentile(entries.map { it.ms }, 0.95),
            p95Chars = percentile(entries.map { it.chars.toLong() }, 0.95).toInt(),
            emptyRate = rate(entries.count { it.empty }, entries.size),
            busyRate = rate(entries.count { it.busy }, entries.size),
            byTool = entries.groupBy { it.tool }.toSortedMap().mapValues { (_, calls) ->
                val times = calls.map { it.ms }
                ToolLatency(calls.size, percentile(times, 0.5), percentile(times, 0.95))
            },
        )
    }

    private fun rate(count: Int, of: Int) = Math.round(count * 1000.0 / of) / 1000.0

    /** Nearest rank: the smallest value that at least [p] of the values do not exceed. */
    private fun percentile(values: List<Long>, p: Double): Long = values.sorted()[ceil(p * values.size).toInt().coerceAtLeast(1) - 1]

    private companion object {
        const val SIZE = 1000
    }
}
