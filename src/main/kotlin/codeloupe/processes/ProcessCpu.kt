package codeloupe.processes

import java.time.Duration
import codeloupe.platform.ToolOutput

/** CPU time a process has used, in ms: from the JDK, else (macOS reports none) from `ps`. 0 when nothing says. */
internal object ProcessCpu {
    fun ms(handle: ProcessHandle): Long =
        handle.info().totalCpuDuration().map(Duration::toMillis).orElse(null) ?: ps(handle.pid()) ?: 0

    // `ps -o time=`: [[dd-]hh:]mm:ss, on macOS mm:ss.cc.
    internal fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val days = trimmed.substringBefore('-', "").toLongOrNull() ?: 0
        val clock = if ('-' in trimmed) trimmed.substringAfter('-') else trimmed
        val parts = clock.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        return (((days * 24 + hours) * 60 + minutes) * 60_000 + Math.round(seconds * 1000))
    }

    private fun ps(pid: Long): Long? = parse(ToolOutput.read(5, "ps", "-o", "time=", "-p", pid.toString()))
}
