package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * The exit code of a process this one did not start — a job that a restarted daemon found still running. The JDK reports it only
 * for its own children. On Windows a handle opened while the process runs keeps its exit code readable after it ends; elsewhere
 * only the parent can reap it, so there is none.
 */
object ExitCodes {
    /** Opens [pid] for its exit code, or null off Windows or when the OS refuses. */
    fun watch(pid: Long): Watch? {
        if (!NativeCalls.isWindows) return null
        return runCatching {
            val handle = OPEN_PROCESS.invoke(SYNCHRONIZE or PROCESS_QUERY_LIMITED_INFORMATION, 0, pid.toInt()) as MemorySegment
            handle.takeIf { it.address() != 0L }?.let(::Watch)
        }.getOrNull()
    }

    class Watch internal constructor(private val handle: MemorySegment) : AutoCloseable {
        private var open = true

        /** The exit code once the process has ended; null while it runs or when it cannot be read. */
        @Synchronized
        fun exitCode(): Int? {
            if (!open) return null
            return runCatching {
                Arena.ofConfined().use { arena ->
                    val code = arena.allocate(ValueLayout.JAVA_INT)
                    val ok = (GET_EXIT_CODE.invoke(handle, code) as Int) != 0
                    code.get(ValueLayout.JAVA_INT, 0).takeIf { ok && it != STILL_ACTIVE }
                }
            }.getOrNull()
        }

        @Synchronized
        override fun close() {
            if (open) runCatching { CLOSE.invoke(handle) }
            open = false
        }
    }

    private val OPEN_PROCESS by lazy {
        NativeCalls.kernel32("OpenProcess", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
    }
    private val GET_EXIT_CODE by lazy {
        NativeCalls.kernel32("GetExitCodeProcess", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    }
    private val CLOSE by lazy { NativeCalls.kernel32("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }

    private const val SYNCHRONIZE = 0x00100000
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val STILL_ACTIVE = 259
}
