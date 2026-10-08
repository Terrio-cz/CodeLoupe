package codeloupe.platform

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

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
        setpriority.invoke(PRIO_PROCESS, 0, NICE) as Int
    }

    private const val BELOW_NORMAL_PRIORITY_CLASS = 0x4000
    private const val PRIO_PROCESS = 0
    private const val NICE = 10
}
