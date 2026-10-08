package codeloupe.processes

import codeloupe.platform.NativeCalls
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name

/**
 * The processes of this machine: pid, start and CPU from the JDK, the rest from the OS-specific [ProcessDetails]; for a Gradle daemon
 * also its last build directory and state from the log under one of [gradleHomes].
 */
class SystemProcesses(private val gradleHomes: List<Path> = GradleDaemonLog.homes(null)) : ProcessSource {
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
            // The OS-specific read is the whole line; the JDK's is cut where Linux limits it, which loses the end of a long class path.
            val commandLine = details?.commandLine ?: info.commandLine().orElse(null)
            val daemon = commandLine?.takeIf { ProcessKind.of(it) == ProcessKind.GRADLE_DAEMON }?.let { GradleDaemonLog.read(it, pid, gradleHomes) }
            ProcessInfo(
                pid = pid, startMs = info.startInstant().map { it.toEpochMilli() }.orElse(0),
                name = info.command().map { runCatching { Path(it).name }.getOrDefault(it) }.orElse(""),
                commandLine = commandLine, cwd = details?.cwd, rssBytes = details?.rssBytes,
                cpuMs = info.totalCpuDuration().map { it.toMillis() }.orElse(null), buildDir = daemon?.lastBuildDir, busy = daemon?.busy,
            )
        }.toList()
    }
}
