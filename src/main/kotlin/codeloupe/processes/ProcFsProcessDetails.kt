package codeloupe.processes

import java.nio.file.Files
import java.nio.file.Path

/** Linux: the working directory, command line and resident size of a process from `/proc/<pid>`. */
internal object ProcFsProcessDetails {
    val available: Boolean get() = Files.isDirectory(Path.of("/proc/self"))

    fun read(pid: Long): ProcessDetails? {
        val dir = Path.of("/proc/$pid")
        val cwd = runCatching { Files.readSymbolicLink(dir.resolve("cwd")).toString() }.getOrNull()?.let(::withoutDeleted)
        val commandLine = runCatching { commandLine(Files.readAllBytes(dir.resolve("cmdline"))) }.getOrNull()?.ifEmpty { null }
        val rss = runCatching {
            Files.readAllLines(dir.resolve("status")).firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLong()?.times(KB)
        }.getOrNull()
        return if (cwd == null && commandLine == null && rss == null) null else ProcessDetails(cwd, commandLine, rss)
    }

    // Linux spells the working directory of a process whose directory was removed `/ws (deleted)`; it is the workspace's own path that a
    // registry and a process listing must agree on. A directory that really is called so, and exists, is left as it is.
    internal fun withoutDeleted(link: String): String =
        if (link.endsWith(DELETED) && !Files.exists(Path.of(link))) link.removeSuffix(DELETED) else link

    /** `/proc/<pid>/cmdline`: the arguments, each ended by a NUL, as one line. */
    internal fun commandLine(raw: ByteArray): String = String(raw, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.joinToString(" ")

    private const val DELETED = " (deleted)"
    private const val KB = 1024L
}
