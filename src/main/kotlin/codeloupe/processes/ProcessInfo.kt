package codeloupe.processes

/**
 * One running process as the OS shows it. [cwd] and [commandLine] are null where the OS does not tell (another user's
 * process, a protected one); [startMs] with the pid identifies a process even after the OS reuses the pid.
 */
data class ProcessInfo(
    val pid: Long,
    val parentPid: Long?,
    val startMs: Long,
    val name: String,
    val commandLine: String?,
    val cwd: String?,
    val rssBytes: Long?,
    /** CPU time used so far, to tell an idle process from a busy one by two readings. */
    val cpuMs: Long?,
) {
    val kind: ProcessKind get() = commandLine?.let(ProcessKind::of) ?: ProcessKind.OTHER
}
