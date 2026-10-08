package codeloupe.processes

import codeloupe.platform.NativeCalls
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * The working directory, command line and working set of a process of this user, read from the process itself: the PEB
 * names its parameter block, and the block holds both as UNICODE_STRINGs. The offsets are those of 64-bit Windows 7 to 11.
 * A process this user may not open (another user's, a protected one) gives null.
 */
internal object WindowsProcessDetails {
    fun read(pid: Long): ProcessDetails? = runCatching { open(pid)?.let { handle -> try { details(handle) } finally { CLOSE.invoke(handle) } } }.getOrNull()

    private fun open(pid: Long): MemorySegment? {
        val handle = OPEN.invoke(PROCESS_QUERY_INFORMATION or PROCESS_VM_READ, 0, pid.toInt()) as MemorySegment
        return handle.takeIf { it.address() != 0L }
    }

    private fun details(process: MemorySegment): ProcessDetails = Arena.ofConfined().use { arena ->
        val rss = workingSet(process, arena)
        val parameters = parameters(process, arena) ?: return ProcessDetails(null, null, rss)
        val cwd = string(process, arena, parameters + CURRENT_DIRECTORY)?.trimEnd('\\', '/')?.ifEmpty { null }
        ProcessDetails(cwd, string(process, arena, parameters + COMMAND_LINE), rss)
    }

    private fun workingSet(process: MemorySegment, arena: Arena): Long? {
        val counters = arena.allocate(COUNTERS_SIZE)
        return if ((MEMORY_INFO.invoke(process, counters, COUNTERS_SIZE.toInt()) as Int) != 0) counters.get(ValueLayout.JAVA_LONG, WORKING_SET_OFFSET) else null
    }

    // PROCESS_BASIC_INFORMATION: ExitStatus, PebBaseAddress (offset 8), …; PEB.ProcessParameters is at offset 0x20.
    private fun parameters(process: MemorySegment, arena: Arena): Long? {
        val basic = arena.allocate(BASIC_INFORMATION_SIZE)
        if ((QUERY.invoke(process, PROCESS_BASIC_INFORMATION, basic, BASIC_INFORMATION_SIZE.toInt(), MemorySegment.NULL) as Int) < 0) return null
        val peb = basic.get(ValueLayout.JAVA_LONG, PEB_ADDRESS_OFFSET).takeIf { it != 0L } ?: return null
        return pointer(process, arena, peb + PROCESS_PARAMETERS)
    }

    private fun pointer(process: MemorySegment, arena: Arena, address: Long): Long? {
        val buffer = arena.allocate(POINTER_SIZE)
        return if (copy(process, address, buffer, POINTER_SIZE)) buffer.get(ValueLayout.JAVA_LONG, 0).takeIf { it != 0L } else null
    }

    // UNICODE_STRING: Length (bytes), MaximumLength, 4 bytes of padding, Buffer.
    private fun string(process: MemorySegment, arena: Arena, address: Long): String? {
        val header = arena.allocate(UNICODE_STRING_SIZE)
        if (!copy(process, address, header, UNICODE_STRING_SIZE)) return null
        val length = header.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
        val buffer = header.get(ValueLayout.JAVA_LONG, UNICODE_STRING_BUFFER)
        if (length == 0 || buffer == 0L || length > MAX_STRING_BYTES) return null
        val text = arena.allocate(length.toLong())
        return if (copy(process, buffer, text, length.toLong())) String(text.toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_16LE) else null
    }

    private fun copy(process: MemorySegment, from: Long, into: MemorySegment, size: Long): Boolean =
        (READ.invoke(process, MemorySegment.ofAddress(from), into, size, MemorySegment.NULL) as Int) != 0

    private val OPEN by lazy {
        NativeCalls.kernel32("OpenProcess", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
    }
    private val CLOSE by lazy { NativeCalls.kernel32("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)) }
    private val READ by lazy {
        NativeCalls.kernel32(
            "ReadProcessMemory",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        )
    }
    private val MEMORY_INFO by lazy {
        NativeCalls.kernel32("K32GetProcessMemoryInfo", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
    }
    private val QUERY by lazy {
        NativeCalls.ntdll(
            "NtQueryInformationProcess",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
    }

    private const val PROCESS_QUERY_INFORMATION = 0x0400
    private const val PROCESS_VM_READ = 0x0010
    private const val PROCESS_BASIC_INFORMATION = 0
    private const val BASIC_INFORMATION_SIZE = 48L
    private const val PEB_ADDRESS_OFFSET = 8L
    private const val PROCESS_PARAMETERS = 0x20L
    private const val CURRENT_DIRECTORY = 0x38L
    private const val COMMAND_LINE = 0x70L
    private const val POINTER_SIZE = 8L
    private const val UNICODE_STRING_SIZE = 16L
    private const val UNICODE_STRING_BUFFER = 8L
    private const val MAX_STRING_BYTES = 64 * 1024
    private const val COUNTERS_SIZE = 72L
    private const val WORKING_SET_OFFSET = 16L
}
