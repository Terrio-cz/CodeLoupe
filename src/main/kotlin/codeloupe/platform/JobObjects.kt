package codeloupe.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Windows job objects. The daemon puts itself into one with KILL_ON_JOB_CLOSE, so everything it starts dies with it,
 * however it exits: the parse worker, git and build tools never run on as orphans. Jobs are the exception: they break away
 * from it ([inOwnJob], BREAKAWAY_OK) into a kill-on-close job object of their own, which a small watcher keeps open while the
 * job runs, so a job outlives its daemon only until the next daemon takes it over. Without a watcher a job's object lives as
 * long as the daemon's handle to it. Elsewhere a no-op.
 */
object JobObjects {
    /** True once this process sits in its own kill-on-close job: what has to outlive it must break away from it. */
    @Volatile
    var inOwnJob: Boolean = false
        private set

    /** Puts this process into a kill-on-close job that lets chosen children break away; true when it is in one. */
    fun enterSelf(): Boolean {
        if (!NativeCalls.isWindows) return false
        return runCatching {
            // Never closed: closing the last handle would end this process too.
            val job = create(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE or JOB_OBJECT_LIMIT_BREAKAWAY_OK) ?: return false
            ((ASSIGN.invoke(job, CURRENT_PROCESS.invoke() as MemorySegment) as Int) != 0).also { inOwnJob = it }
        }.getOrDefault(false)
    }

    /** A job object for one process tree that is already running (it stays in this process's job as well), or null off Windows or when the OS refuses. */
    fun forProcess(pid: Long): Tree? {
        if (!NativeCalls.isWindows) return null
        return runCatching {
            val job = create(0) ?: return null
            val process = OPEN_PROCESS.invoke(PROCESS_SET_QUOTA or PROCESS_TERMINATE, 0, pid.toInt()) as MemorySegment
            val assigned = process.address() != 0L && try {
                (ASSIGN.invoke(job, process) as Int) != 0
            } finally {
                CLOSE.invoke(process)
            }
            if (assigned) Tree(job, killOnClose = false) else null.also { CLOSE.invoke(job) }
        }.getOrNull()
    }

    /** A kill-on-close job object for processes created into it suspended ([WindowsProcesses]); null off Windows or when the OS refuses. */
    fun killOnClose(): Tree? {
        if (!NativeCalls.isWindows) return null
        return runCatching { create(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)?.let { Tree(it, killOnClose = true) } }.getOrNull()
    }

    /** One job's process tree. [close] lets the processes run on, unless the object kills on close and this was its last handle. */
    class Tree internal constructor(internal val handle: MemorySegment, val killOnClose: Boolean) : AutoCloseable {
        private var open = true

        /** Puts the process behind [process] (a handle with PROCESS_SET_QUOTA and PROCESS_TERMINATE) into this job. */
        @Synchronized
        internal fun assign(process: MemorySegment): Boolean = open && (ASSIGN.invoke(handle, process) as Int) != 0

        /** How many processes still run in this job; null when it cannot be told. */
        @Synchronized
        fun processes(): Int? {
            if (!open) return null
            return runCatching {
                Arena.ofConfined().use { arena ->
                    val info = arena.allocate(ACCOUNTING_SIZE)
                    val ok = (QUERY_INFORMATION.invoke(handle, JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION, info, ACCOUNTING_SIZE.toInt(), MemorySegment.NULL) as Int) != 0
                    info.get(ValueLayout.JAVA_INT, ACTIVE_PROCESSES_OFFSET).takeIf { ok }
                }
            }.getOrNull()
        }

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

    private fun create(limits: Int): MemorySegment? {
        val job = CREATE.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (job.address() == 0L || limits == 0) return job.takeIf { it.address() != 0L }
        val info = Arena.ofConfined().use { arena ->
            val struct = arena.allocate(EXTENDED_LIMIT_SIZE)
            struct.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, limits)
            SET_INFORMATION.invoke(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, struct, EXTENDED_LIMIT_SIZE.toInt()) as Int
        }
        if (info == 0) CLOSE.invoke(job)
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
    private val QUERY_INFORMATION by lazy {
        NativeCalls.kernel32(
            "QueryInformationJobObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
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
    private const val JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x0800
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000

    // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION: four 64-bit times, then page faults, total, active and terminated processes.
    private const val JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1
    private const val ACCOUNTING_SIZE = 48L
    private const val ACTIVE_PROCESSES_OFFSET = 40L
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001
}
