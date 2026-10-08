package codeloupe.overlay

import codeloupe.platform.NativeCalls
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * A directory listing straight from `FindFirstFileExW`: basic information (no 8.3 short name lookup per entry) and
 * large fetches, no `Path` or attribute object per entry. About 2.5x faster than the JDK's walk on Terrio (1 200
 * directories, 3 700 entries, 4 threads: 17 ms against 48). Answers like [JdkListing]; null when anything is unusual
 * (a failure, a directory that is gone or unreadable), so the caller lists that directory the JDK way.
 */
internal object WindowsListing {
    private class Calls(val first: MethodHandle, val next: MethodHandle, val close: MethodHandle)

    private val calls: Calls? by lazy {
        if (!NativeCalls.isWindows) return@lazy null
        runCatching {
            val state = Linker.Option.captureCallState("GetLastError")
            Calls(
                NativeCalls.kernel32(
                    "FindFirstFileExW",
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ),
                ),
                NativeCalls.kernel32(
                    "FindNextFileW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                    state,
                ),
                NativeCalls.kernel32("FindClose", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)),
            )
        }.getOrNull()
    }

    val available: Boolean get() = calls != null

    /** Entries of [dir] without `.` and `..`, or null when the JDK should list it. */
    fun list(dir: Path): List<DirEntry>? {
        val calls = calls ?: return null
        val pattern = pattern(dir)
        return try {
            Arena.ofConfined().use { arena ->
                val data = arena.allocate(DATA_BYTES, 8)
                val handle = calls.first.invoke(
                    arena.allocateFrom(pattern, StandardCharsets.UTF_16LE), FIND_EX_INFO_BASIC, data,
                    FIND_EX_SEARCH_NAME_MATCH, MemorySegment.NULL, FIND_FIRST_EX_LARGE_FETCH,
                ) as Long
                if (handle == INVALID_HANDLE) return@use null
                try {
                    read(calls, handle, data, arena)
                } finally {
                    calls.close.invoke(handle) as Int
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // Beyond MAX_PATH the extended-length form (prefix backslash backslash question mark backslash) is needed, which skips
    // the normalisation: the absolute, normalised path is spelled out.
    private fun pattern(dir: Path): String {
        val path = dir.toString()
        if (path.length <= MAX_DIR_CHARS) return path + ALL
        val absolute = dir.toAbsolutePath().normalize().toString()
        return (if (absolute.startsWith(UNC)) EXTENDED_UNC + absolute.substring(1) else EXTENDED + absolute) + ALL
    }

    private fun read(calls: Calls, handle: Long, data: MemorySegment, arena: Arena): List<DirEntry>? {
        val state = arena.allocate(Linker.Option.captureStateLayout())
        val errorAt = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))
        val entries = ArrayList<DirEntry>()
        do {
            entry(data)?.let { entries += it }
            val more = calls.next.invoke(state, handle, data) as Int
            if (more == 0) return if (state.get(ValueLayout.JAVA_INT, errorAt) == ERROR_NO_MORE_FILES) entries else null
        } while (true)
    }

    private fun entry(data: MemorySegment): DirEntry? {
        val name = data.getString(NAME_OFFSET, StandardCharsets.UTF_16LE)
        if (name == "." || name == "..") return null
        val attributes = data.get(ValueLayout.JAVA_INT_UNALIGNED, 0)
        val reparse = attributes and REPARSE_POINT != 0
        // As the JDK reports them: only a symbolic link is neither; other reparse points (junctions) read as directories.
        val link = reparse && data.get(ValueLayout.JAVA_INT_UNALIGNED, REPARSE_TAG_OFFSET) == IO_REPARSE_TAG_SYMLINK
        val directory = !link && attributes and DIRECTORY != 0
        val regular = !link && !directory && attributes and (REPARSE_POINT or DEVICE) == 0
        val written = data.get(ValueLayout.JAVA_LONG_UNALIGNED, WRITE_TIME_OFFSET)
        val size = (data.get(ValueLayout.JAVA_INT_UNALIGNED, SIZE_HIGH_OFFSET).toLong() shl 32) or (data.get(ValueLayout.JAVA_INT_UNALIGNED, SIZE_LOW_OFFSET).toLong() and 0xFFFFFFFFL)
        return DirEntry(name, directory, regular, written / 10 - WINDOWS_EPOCH_MICROS, size)
    }

    // WIN32_FIND_DATAW: attributes, three FILETIMEs, size high and low, two reserved DWORDs, the name (260 UTF-16 units), the 8.3 name.
    private const val DATA_BYTES = 592L
    private const val WRITE_TIME_OFFSET = 20L
    private const val SIZE_HIGH_OFFSET = 28L
    private const val SIZE_LOW_OFFSET = 32L
    private const val REPARSE_TAG_OFFSET = 36L
    private const val NAME_OFFSET = 44L

    private const val FIND_EX_INFO_BASIC = 1
    private const val FIND_EX_SEARCH_NAME_MATCH = 0
    private const val FIND_FIRST_EX_LARGE_FETCH = 2
    private const val INVALID_HANDLE = -1L
    private const val ERROR_NO_MORE_FILES = 18
    private const val DIRECTORY = 0x10
    private const val DEVICE = 0x40
    private const val REPARSE_POINT = 0x400
    private const val IO_REPARSE_TAG_SYMLINK = 0xA000000C.toInt()
    private const val MAX_DIR_CHARS = 240
    private const val ALL = "\\*"
    private const val UNC = "\\\\"
    private const val EXTENDED = "\\\\?\\"
    private const val EXTENDED_UNC = "\\\\?\\UNC"
    private const val WINDOWS_EPOCH_MICROS = 11_644_473_600_000_000L
}
