package codeloupe.daemon

import codeloupe.platform.IsoTime
import codeloupe.platform.ProcessMemory
import kotlinx.serialization.Serializable
import java.time.Instant

/** One reading of the daemon's memory and CPU time. */
@Serializable
data class ResourceSample(val t: String, val rssMb: Long?, val heapMb: Long, val cpuSec: Long)

/**
 * RSS and CPU readings taken while the daemon is used, at most one per [INTERVAL_MS], the last [SIZE] kept (about four
 * hours of continuous use). Nothing runs while it is idle, so an idle stretch is a gap, not a flat line.
 */
class ResourceHistory(private val now: () -> Instant = Instant::now) {
    private val samples = ArrayDeque<ResourceSample>()
    private var lastAt = Instant.EPOCH

    @Synchronized
    fun sample() {
        val at = now()
        if (at.toEpochMilli() - lastAt.toEpochMilli() < INTERVAL_MS) return
        lastAt = at
        val runtime = Runtime.getRuntime()
        val cpu = ProcessHandle.current().info().totalCpuDuration().map { it.toSeconds() }.orElse(0)
        samples.addLast(ResourceSample(IsoTime.of(at), ProcessMemory.rssMb(), (runtime.totalMemory() - runtime.freeMemory()) / MB, cpu))
        if (samples.size > SIZE) samples.removeFirst()
    }

    @Synchronized
    fun list(): List<ResourceSample> = samples.toList()

    private companion object {
        const val INTERVAL_MS = 60_000L
        const val SIZE = 240
        const val MB = 1024L * 1024
    }
}
