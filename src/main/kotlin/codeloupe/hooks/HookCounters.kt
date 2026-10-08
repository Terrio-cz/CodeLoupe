package codeloupe.hooks

/** The running tally behind [HookStats]; the latency is kept for the last [WINDOW] calls. */
class HookCounters {
    private var calls = 0L
    private var advised = 0L
    private var denied = 0L
    private var sessions = 0L
    private val passed = sortedMapOf<String, Long>()
    private val micros = ArrayDeque<Long>()

    @Synchronized
    fun advised(nanos: Long) = record(nanos) { advised++ }

    @Synchronized
    fun denied(nanos: Long) = record(nanos) { denied++ }

    @Synchronized
    fun sessionStarted(nanos: Long) = record(nanos) { sessions++ }

    @Synchronized
    fun passed(reason: String, nanos: Long) = record(nanos) { passed.merge(reason, 1L, Long::plus) }

    private fun record(nanos: Long, count: () -> Unit) {
        calls++
        count()
        micros.addLast(nanos / 1_000)
        if (micros.size > WINDOW) micros.removeFirst()
    }

    @Synchronized
    fun snapshot(): HookStats {
        val sorted = micros.sorted()
        fun at(share: Double) = if (sorted.isEmpty()) 0.0 else sorted[minOf(sorted.size - 1, (sorted.size * share).toInt())] / 1_000.0
        return HookStats(calls, advised, denied, sessions, passed.toMap(), at(0.5), at(0.95))
    }

    private companion object {
        const val WINDOW = 500
    }
}
