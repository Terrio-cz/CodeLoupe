package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A Windows job object with KILL_ON_JOB_CLOSE: processes assigned to it (and what they start afterwards) die when
 * this process exits, however it exits. Jobs of a crashed daemon therefore never run on as orphans. Elsewhere a no-op;
 * the daemon kills leftovers by pid when it starts again.
 */
object KillOnCloseJob {
    private val handle: MemorySegment? by lazy { runCatching { create() }.getOrNull() }

    /** True when [pid] is now in the job. */
    fun assign(pid: Long): Boolean {
        if (!NativeCalls.isWindows) return false
        val job = handle ?: return false
        return runCatching {
            val process = OPEN_PROCESS.invoke(PROCESS_SET_QUOTA or PROCESS_TERMINATE, 0, pid.toInt()) as MemorySegment
            if (process.address() == 0L) return false
            try {
                (ASSIGN.invoke(job, process) as Int) != 0
            } finally {
                CLOSE.invoke(process)
            }
        }.getOrDefault(false)
    }

    private fun create(): MemorySegment? {
        if (!NativeCalls.isWindows) return null
        val job = CREATE.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (job.address() == 0L) return null
        val info = Arena.global().allocate(EXTENDED_LIMIT_SIZE)
        info.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        val ok = SET_INFORMATION.invoke(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, EXTENDED_LIMIT_SIZE.toInt()) as Int
        return job.takeIf { ok != 0 }
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
    private val ASSIGN by lazy {
        NativeCalls.kernel32("AssignProcessToJobObject", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
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
