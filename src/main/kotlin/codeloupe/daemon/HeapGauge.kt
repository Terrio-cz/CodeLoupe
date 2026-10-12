package codeloupe.daemon

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicLong

/**
 * What the daemon's heap really holds: each heap pool as its last collection left it. The used heap of [Runtime] counts
 * garbage not yet collected and says little about how close the daemon is to its limit; this does (CL-238).
 */
object HeapGauge {
    private const val MB = 1024L * 1024
    private val peak = AtomicLong()

    /** Megabytes live after the last collections; raises [peakMb]. */
    fun liveMb(): Long {
        val live = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.collectionUsage?.used ?: 0L } / MB
        peak.accumulateAndGet(live, ::maxOf)
        return live
    }

    /** The largest [liveMb] read since the daemon started. */
    fun peakMb(): Long = peak.get()

    fun limitMb(): Long = Runtime.getRuntime().maxMemory() / MB
}
