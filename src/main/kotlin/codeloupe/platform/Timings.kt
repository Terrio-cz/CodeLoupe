package codeloupe.platform

import java.util.concurrent.atomic.LongAdder

/**
 * Where the daemon spends its time, summed since it started: `/status` shows it and the profile script
 * (`tools/profile.mjs`) diffs it around each query. Parts can overlap: a query reads while its worktree is checked.
 */
object Timings {
    private val nanos = TimedPart.entries.map { LongAdder() }
    private val counts = TimedPart.entries.map { LongAdder() }
    private val spawns = LongAdder()

    inline fun <T> measure(part: TimedPart, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(part, System.nanoTime() - start)
        }
    }

    fun add(part: TimedPart, elapsedNanos: Long) {
        nanos[part.ordinal].add(elapsedNanos)
        counts[part.ordinal].increment()
    }

    /** Counts a started git process. */
    fun spawned() = spawns.increment()

    fun gitSpawns(): Long = spawns.sum()

    fun snapshot(): Map<String, PartTime> =
        TimedPart.entries.associate { it.name.lowercase() to PartTime(counts[it.ordinal].sum(), nanos[it.ordinal].sum() / 1_000) }
}
