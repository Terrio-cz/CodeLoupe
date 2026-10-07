package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/** Resident set size of this process in MB (current and peak), or null when the OS does not say. */
object ProcessMemory {
    fun rssMb(): Long? = runCatching { if (NativeCalls.isWindows) windowsCounter(WORKING_SET) else procStatus("VmRSS:") ?: psRssMb() }.getOrNull()

    fun peakRssMb(): Long? = runCatching { if (NativeCalls.isWindows) windowsCounter(PEAK_WORKING_SET) else procStatus("VmHWM:") ?: rusageMaxRssMb() }.getOrNull()

    // PROCESS_MEMORY_COUNTERS: cb, PageFaultCount, PeakWorkingSetSize (offset 8), WorkingSetSize (offset 16), …
    private fun windowsCounter(offset: Long): Long {
        val current = NativeCalls.kernel32("GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS))
        val info = NativeCalls.kernel32(
            "K32GetProcessMemoryInfo",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        Arena.ofConfined().use { arena ->
            val counters = arena.allocate(COUNTERS_SIZE)
            info.invoke(current.invoke() as MemorySegment, counters, COUNTERS_SIZE.toInt()) as Int
            return counters.get(ValueLayout.JAVA_LONG, offset) / MB
        }
    }

    private fun procStatus(field: String): Long? {
        val status = Path.of("/proc/self/status")
        if (!Files.exists(status)) return null
        return Files.readAllLines(status).firstOrNull { it.startsWith(field) }?.filter(Char::isDigit)?.toLong()?.div(1024)
    }

    // macOS has no /proc: ask `ps`, at most every PS_TTL_MS, since every CLI call reads /status.
    @Volatile
    private var psSample: Pair<Long, Long?>? = null

    private fun psRssMb(): Long? {
        psSample?.takeIf { System.currentTimeMillis() - it.first < PS_TTL_MS }?.let { return it.second }
        val process = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start()
        val kb = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim().toLongOrNull()
        process.waitFor()
        return kb?.div(1024).also { psSample = System.currentTimeMillis() to it }
    }

    // macOS: getrusage(RUSAGE_SELF).ru_maxrss is in bytes and follows two 16-byte timevals.
    private fun rusageMaxRssMb(): Long {
        val getrusage = NativeCalls.libc("getrusage", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
        Arena.ofConfined().use { arena ->
            val usage = arena.allocate(256)
            getrusage.invoke(0, usage) as Int
            return usage.get(ValueLayout.JAVA_LONG, 32) / MB
        }
    }

    private const val PS_TTL_MS = 10_000L
    private const val COUNTERS_SIZE = 72L
    private const val PEAK_WORKING_SET = 8L
    private const val WORKING_SET = 16L
    private const val MB = 1024L * 1024
}
