package codeloupe.processes

import codeloupe.platform.NativeCalls
import kotlin.io.path.Path
import kotlin.io.path.name

/** The processes of this machine: pid, parent, start and CPU from the JDK, the rest from the OS-specific [ProcessDetails]. */
class SystemProcesses : ProcessSource {
    override fun read(): List<ProcessInfo> {
        val table = if (NativeCalls.isWindows || ProcFsProcessDetails.available) null else PsProcessDetails.readAll()
        return ProcessHandle.allProcesses().map { handle ->
            val info = handle.info()
            val pid = handle.pid()
            val details = when {
                NativeCalls.isWindows -> WindowsProcessDetails.read(pid)
                table != null -> table[pid]
                else -> ProcFsProcessDetails.read(pid)
            }
            ProcessInfo(
                pid = pid, parentPid = handle.parent().map { it.pid() }.orElse(null), startMs = info.startInstant().map { it.toEpochMilli() }.orElse(0),
                name = info.command().map { runCatching { Path(it).name }.getOrDefault(it) }.orElse(""),
                commandLine = info.commandLine().orElse(null) ?: details?.commandLine, cwd = details?.cwd, rssBytes = details?.rssBytes,
                cpuMs = info.totalCpuDuration().map { it.toMillis() }.orElse(null),
            )
        }.toList()
    }
}
