package codeloupe.processes

import java.nio.file.Files
import java.nio.file.Path

/** Linux: the working directory, command line and resident size of a process from `/proc/<pid>`. */
internal object ProcFsProcessDetails {
    val available: Boolean get() = Files.isDirectory(Path.of("/proc/self"))

    fun read(pid: Long): ProcessDetails? {
        val dir = Path.of("/proc/$pid")
        val cwd = runCatching { Files.readSymbolicLink(dir.resolve("cwd")).toString() }.getOrNull()
        val commandLine = runCatching { String(Files.readAllBytes(dir.resolve("cmdline")), Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.joinToString(" ") }.getOrNull()?.ifEmpty { null }
        val rss = runCatching {
            Files.readAllLines(dir.resolve("status")).firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLong()?.times(KB)
        }.getOrNull()
        return if (cwd == null && commandLine == null && rss == null) null else ProcessDetails(cwd, commandLine, rss)
    }

    private const val KB = 1024L
}
