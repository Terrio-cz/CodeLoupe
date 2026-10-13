package codeloupe.platform

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/**
 * `CreateProcessW` for what ProcessBuilder cannot ask for: a process created suspended, so it is in its job object before it
 * runs any code, optionally breaking away from this process's job, and inheriting exactly the handles listed (a handle list:
 * never the pipes of a ProcessBuilder child started at the same time).
 */
internal object WindowsProcesses {
    /**
     * A suspended process: [resume] lets it run, [abandon] ends it unrun. [process] (PROCESS_ALL_ACCESS) stays open until [close], or until
     * whoever took it over (an [ExitCodes.Watch]) closes it.
     */
    class Created internal constructor(val pid: Long, val process: MemorySegment, private val thread: MemorySegment) {
        fun resume() {
            RESUME.invoke(thread) as Int
            CLOSE.invoke(thread) as Int
        }

        fun abandon() {
            runCatching { TERMINATE_PROCESS.invoke(process, 1) as Int }
            runCatching { CLOSE.invoke(thread) as Int }
            close()
        }

        fun close() {
            runCatching { CLOSE.invoke(process) as Int }
        }

        /** Hands [process] over to a watch of its exit code, which closes it. */
        fun exitWatch(): ExitCodes.Watch = ExitCodes.Watch(process)
    }

    /** CreateProcessW failed with the Windows error [code]. */
    class Failed(val code: Int, program: String) : IOException("Cannot run program \"$program\": CreateProcess error=$code, ${describe(code)}")

    /**
     * Starts [commandLine] suspended. [environment] null inherits this process's; [stdio] gives its standard handles. [inherit] are
     * further handles it gets, each an [inheritable] copy: a child holds them under the same values.
     */
    fun create(
        program: String, commandLine: String, cwd: Path?, environment: Map<String, String>?, stdio: Stdio?, inherit: List<MemorySegment>, breakaway: Boolean,
    ): Created = Arena.ofConfined().use { arena ->
        val handles = listOfNotNull(stdio?.stdin, stdio?.stdout) + inherit
        val info = arena.allocate(STARTUPINFOEX_SIZE, 8)
        info.set(ValueLayout.JAVA_INT, 0, STARTUPINFOEX_SIZE.toInt())
        if (stdio != null) {
            info.set(ValueLayout.JAVA_INT, FLAGS_OFFSET, STARTF_USESTDHANDLES)
            info.set(ValueLayout.ADDRESS, STDIN_OFFSET, stdio.stdin)
            info.set(ValueLayout.ADDRESS, STDOUT_OFFSET, stdio.stdout)
            info.set(ValueLayout.ADDRESS, STDERR_OFFSET, stdio.stdout)
        }
        val attributes = if (handles.isEmpty()) null else handleList(handles, arena)
        info.set(ValueLayout.ADDRESS, ATTRIBUTE_LIST_OFFSET, attributes ?: MemorySegment.NULL)
        val flags = CREATE_SUSPENDED or CREATE_UNICODE_ENVIRONMENT or CREATE_NO_WINDOW or EXTENDED_STARTUPINFO_PRESENT or
            (if (breakaway) CREATE_BREAKAWAY_FROM_JOB else 0)
        val processInfo = arena.allocate(PROCESS_INFORMATION_SIZE, 8)
        val state = arena.allocate(Linker.Option.captureStateLayout())
        try {
            val ok = CREATE_PROCESS.invoke(
                state, MemorySegment.NULL, wide(commandLine, arena), MemorySegment.NULL, MemorySegment.NULL,
                if (handles.isEmpty()) 0 else 1, flags, environment?.let { block(it, arena) } ?: MemorySegment.NULL,
                cwd?.let { wide(it.toString(), arena) } ?: MemorySegment.NULL, info, processInfo,
            ) as Int
            if (ok == 0) throw Failed(state.get(ValueLayout.JAVA_INT, ERROR_OFFSET), program)
        } finally {
            attributes?.let { DELETE_ATTRIBUTE_LIST.invoke(it) }
        }
        Created(
            processInfo.get(ValueLayout.JAVA_INT, PID_OFFSET).toLong() and 0xFFFFFFFFL,
            processInfo.get(ValueLayout.ADDRESS, 0),
            processInfo.get(ValueLayout.ADDRESS, THREAD_OFFSET),
        )
    }

    /**
     * An inheritable copy of [handle], for [create]; [close] it once the child is created. Children ProcessBuilder starts meanwhile
     * may inherit it as well, so it lives only for that moment.
     */
    fun inheritable(handle: MemorySegment): MemorySegment = Arena.ofConfined().use { arena ->
        val target = arena.allocate(ValueLayout.ADDRESS)
        val current = CURRENT_PROCESS.invoke() as MemorySegment
        val ok = DUPLICATE.invoke(current, handle, current, target, 0, 1, DUPLICATE_SAME_ACCESS) as Int
        if (ok == 0) throw IOException("cannot duplicate a handle for a child process")
        target.get(ValueLayout.ADDRESS, 0)
    }

    fun close(handle: MemorySegment) {
        runCatching { CLOSE.invoke(handle) as Int }
    }

    /** The standard handles of a process: input from NUL, output and errors appended to one file. Close after the process was created. */
    class Stdio private constructor(val stdin: MemorySegment, val stdout: MemorySegment) : AutoCloseable {
        override fun close() {
            runCatching { CLOSE.invoke(stdin) as Int }
            runCatching { CLOSE.invoke(stdout) as Int }
        }

        companion object {
            fun appendTo(log: Path): Stdio {
                val input = open("NUL", GENERIC_READ, OPEN_EXISTING)
                val output = try {
                    // Append-only access: every write lands at the end, whoever else writes the file (as Redirect.appendTo).
                    open(log.toString(), FILE_APPEND_DATA or SYNCHRONIZE, OPEN_ALWAYS)
                } catch (e: IOException) {
                    CLOSE.invoke(input) as Int
                    throw e
                }
                return Stdio(input, output)
            }

            private fun open(path: String, access: Int, disposition: Int): MemorySegment = Arena.ofConfined().use { arena ->
                val security = arena.allocate(SECURITY_ATTRIBUTES_SIZE, 8)
                security.set(ValueLayout.JAVA_INT, 0, SECURITY_ATTRIBUTES_SIZE.toInt())
                security.set(ValueLayout.JAVA_INT, INHERIT_OFFSET, 1)
                val state = arena.allocate(Linker.Option.captureStateLayout())
                val handle = CREATE_FILE.invoke(state, wide(path, arena), access, FILE_SHARE_ALL, security, disposition, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL) as MemorySegment
                if (handle.address() == INVALID_HANDLE) throw IOException("cannot open $path: error ${state.get(ValueLayout.JAVA_INT, ERROR_OFFSET)}")
                handle
            }
        }
    }

    /** The current process's environment block as Windows wants it: `name=value` entries sorted case-insensitively. */
    internal fun blockText(environment: Map<String, String>): String =
        environment.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }).joinToString("") { "${it.key}=${it.value}\u0000" }

    private fun block(environment: Map<String, String>, arena: Arena): MemorySegment {
        val text = blockText(environment).ifEmpty { "\u0000" }
        return wide(text, arena)
    }

    private fun wide(text: String, arena: Arena): MemorySegment {
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val segment = arena.allocate(bytes.size + 2L, 2)
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
        return segment
    }

    private fun handleList(handles: List<MemorySegment>, arena: Arena): MemorySegment {
        val size = arena.allocate(ValueLayout.JAVA_LONG)
        INITIALIZE_ATTRIBUTE_LIST.invoke(MemorySegment.NULL, 1, 0, size) as Int
        val list = arena.allocate(size.get(ValueLayout.JAVA_LONG, 0), 8)
        if ((INITIALIZE_ATTRIBUTE_LIST.invoke(list, 1, 0, size) as Int) == 0) throw IOException("cannot prepare the handle list of a child process")
        // The array must stay valid until the process is created: it lives in the same arena.
        val array = arena.allocate(ValueLayout.ADDRESS, handles.size.toLong())
        handles.forEachIndexed { i, h -> array.setAtIndex(ValueLayout.ADDRESS, i.toLong(), h) }
        val ok = UPDATE_ATTRIBUTE.invoke(list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, array, 8L * handles.size, MemorySegment.NULL, MemorySegment.NULL) as Int
        if (ok == 0) {
            DELETE_ATTRIBUTE_LIST.invoke(list)
            throw IOException("cannot set the handle list of a child process")
        }
        return list
    }

    private fun describe(code: Int): String = when (code) {
        2 -> "The system cannot find the file specified"
        3 -> "The system cannot find the path specified"
        5 -> "Access is denied"
        193 -> "%1 is not a valid Win32 application"
        else -> "Windows error $code"
    }

    private val ERROR_OFFSET = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))
    private val CAPTURE = Linker.Option.captureCallState("GetLastError")

    private val CREATE_PROCESS by lazy {
        NativeCalls.kernel32(
            "CreateProcessW",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
            CAPTURE,
        )
    }
    private val CREATE_FILE by lazy {
        NativeCalls.kernel32(
            "CreateFileW",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
            CAPTURE,
        )
    }
    private val DUPLICATE by lazy {
        NativeCalls.kernel32(
            "DuplicateHandle",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ),
        )
    }
    private val INITIALIZE_ATTRIBUTE_LIST by lazy {
        NativeCalls.kernel32(
            "InitializeProcThreadAttributeList",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
    }
    private val UPDATE_ATTRIBUTE by lazy {
        NativeCalls.kernel32(
            "UpdateProcThreadAttribute",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        )
    }
    private val DELETE_ATTRIBUTE_LIST by lazy { NativeCalls.kernel32("DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)) }
    private val CURRENT_PROCESS by lazy { NativeCalls.kernel32("GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS)) }
    private val RESUME by lazy { NativeCalls.kernel32("ResumeThread", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }
    private val TERMINATE_PROCESS by lazy {
        NativeCalls.kernel32("TerminateProcess", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
    }
    private val CLOSE by lazy { NativeCalls.kernel32("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }

    // STARTUPINFOEXW on 64-bit Windows: STARTUPINFOW (104 bytes) and the attribute list pointer.
    private const val STARTUPINFOEX_SIZE = 112L
    private const val FLAGS_OFFSET = 60L
    private const val STDIN_OFFSET = 80L
    private const val STDOUT_OFFSET = 88L
    private const val STDERR_OFFSET = 96L
    private const val ATTRIBUTE_LIST_OFFSET = 104L

    // PROCESS_INFORMATION: process and thread handles, then process and thread ids.
    private const val PROCESS_INFORMATION_SIZE = 24L
    private const val THREAD_OFFSET = 8L
    private const val PID_OFFSET = 16L

    // SECURITY_ATTRIBUTES: length, descriptor pointer, inherit flag.
    private const val SECURITY_ATTRIBUTES_SIZE = 24L
    private const val INHERIT_OFFSET = 16L

    private const val STARTF_USESTDHANDLES = 0x100
    private const val CREATE_SUSPENDED = 0x4
    private const val CREATE_UNICODE_ENVIRONMENT = 0x400
    private const val EXTENDED_STARTUPINFO_PRESENT = 0x80000
    private const val CREATE_BREAKAWAY_FROM_JOB = 0x1000000
    private const val CREATE_NO_WINDOW = 0x8000000
    private const val PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002L
    private const val DUPLICATE_SAME_ACCESS = 2
    private const val GENERIC_READ = 0x80000000.toInt()
    private const val FILE_APPEND_DATA = 0x4
    private const val SYNCHRONIZE = 0x100000
    private const val FILE_SHARE_ALL = 0x7
    private const val OPEN_EXISTING = 3
    private const val OPEN_ALWAYS = 4
    private const val FILE_ATTRIBUTE_NORMAL = 0x80
    private const val INVALID_HANDLE = -1L
}
