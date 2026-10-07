package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Windows job objects. The daemon puts itself into one with KILL_ON_JOB_CLOSE, so everything it starts dies with it,
 * however it exits: jobs of a crashed daemon never run on as orphans. Each job also gets a job object of its own
 * (nested), so a cancel ends the whole tree, grandchildren whose parent already exited included. Elsewhere a no-op;
 * the daemon kills leftovers by pid when it starts again.
 */
object JobObjects {
    /** Puts this process into a kill-on-close job; true when it is in one. */
    fun enterSelf(): Boolean {
        if (!NativeCalls.isWindows) return false
        return runCatching {
            // Never closed: closing the last handle would end this process too.
            val job = create(killOnClose = true) ?: return false
            (ASSIGN.invoke(job, CURRENT_PROCESS.invoke() as MemorySegment) as Int) != 0
        }.getOrDefault(false)
    }

    /** A job object for one process tree, or null off Windows or when the OS refuses. */
    fun forProcess(pid: Long): Tree? {
        if (!NativeCalls.isWindows) return null
        return runCatching {
            val job = create(killOnClose = false) ?: return null
            val process = OPEN_PROCESS.invoke(PROCESS_SET_QUOTA or PROCESS_TERMINATE, 0, pid.toInt()) as MemorySegment
            val assigned = process.address() != 0L && try {
                (ASSIGN.invoke(job, process) as Int) != 0
            } finally {
                CLOSE.invoke(process)
            }
            if (assigned) Tree(job) else null.also { CLOSE.invoke(job) }
        }.getOrNull()
    }

    /** One job's process tree; [close] lets the processes run on (inside the daemon's job). */
    class Tree internal constructor(private val handle: MemorySegment) : AutoCloseable {
        private var open = true

        @Synchronized
        fun terminate() {
            if (open) runCatching { TERMINATE.invoke(handle, 1) }
        }

        @Synchronized
        override fun close() {
            if (open) runCatching { CLOSE.invoke(handle) }
            open = false
        }
    }

    private fun create(killOnClose: Boolean): MemorySegment? {
        val job = CREATE.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (job.address() == 0L || !killOnClose) return job.takeIf { it.address() != 0L }
        val info = Arena.ofConfined().use { arena ->
            val struct = arena.allocate(EXTENDED_LIMIT_SIZE)
            struct.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            SET_INFORMATION.invoke(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, struct, EXTENDED_LIMIT_SIZE.toInt()) as Int
        }
        return job.takeIf { info != 0 }
    }

    private val CREATE by lazy {
        NativeCalls.kernel32("CreateJobObjectW", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    }
    private val SET_INFORMATION by lazy {
        NativeCalls.kernel32(
            "SetInformationJobObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
    }
    private val OPEN_PROCESS by lazy {
        NativeCalls.kernel32("OpenProcess", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
    }
    private val CURRENT_PROCESS by lazy { NativeCalls.kernel32("GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS)) }
    private val ASSIGN by lazy {
        NativeCalls.kernel32("AssignProcessToJobObject", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    }
    private val TERMINATE by lazy {
        NativeCalls.kernel32("TerminateJobObject", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
    }
    private val CLOSE by lazy { NativeCalls.kernel32("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION on 64-bit Windows: 144 bytes, LimitFlags at offset 16.
    private const val EXTENDED_LIMIT_SIZE = 144L
    private const val LIMIT_FLAGS_OFFSET = 16L
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001
}
