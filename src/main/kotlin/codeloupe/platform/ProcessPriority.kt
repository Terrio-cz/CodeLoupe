package codeloupe.platform

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/** Lowers this process's CPU priority (below normal, nice 10) so background syncs yield to interactive work. */
object ProcessPriority {
    fun lower() {
        runCatching { if (NativeCalls.isWindows) windows() else posix() }
    }

    private fun windows() {
        val current = NativeCalls.kernel32("GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS))
        val setPriorityClass = NativeCalls.kernel32(
            "SetPriorityClass",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        setPriorityClass.invoke(current.invoke() as MemorySegment, BELOW_NORMAL_PRIORITY_CLASS) as Int
    }

    private fun posix() {
        val setpriority = NativeCalls.libc(
            "setpriority",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
        // macOS: PRIO_PROCESS with 0 is the whole process. Linux: it is the calling thread alone, and a thread inherits from the one that
        // started it, so the threads the JVM made before this call (GC, compiler, signal) are lowered one by one by their ids.
        setpriority.invoke(PRIO_PROCESS, 0, NICE) as Int
        repeat(PASSES) {
            for (tid in threadIds()) setpriority.invoke(PRIO_PROCESS, tid, NICE) as Int
        }
    }

    // A thread started during a pass by one not yet lowered is caught by the next one.
    private fun threadIds(): List<Int> = runCatching {
        Files.newDirectoryStream(Path.of("/proc/self/task")).use { tasks -> tasks.mapNotNull { it.fileName.toString().toIntOrNull() } }
    }.getOrDefault(emptyList())

    private const val BELOW_NORMAL_PRIORITY_CLASS = 0x4000
    private const val PRIO_PROCESS = 0
    private const val NICE = 10
    private const val PASSES = 2
}
