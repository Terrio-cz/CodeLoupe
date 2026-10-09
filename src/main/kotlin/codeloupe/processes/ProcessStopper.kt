package codeloupe.processes

import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Stops one build tool of a workspace, after checking again that it is what the plan says: the same process (pid and start
 * time), still a build tool, still in the workspace, and idle. A build that is running, or a process that does not
 * stop, is reported as blocked and tried again later; nothing is ever stopped on the word of the plan alone. The checks run again after
 * the CPU probe, which takes a while: a build may have started in that time. A start time the system will not tell is not proof of identity.
 */
class ProcessStopper(
    private val source: ProcessSource = SystemProcesses(),
    /** How long the CPU time of the process and its children is watched to tell idle from busy. */
    private val probeMs: Long = 600,
    private val graceMs: Long = 5_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val startMsOf: (ProcessHandle) -> Long? = { it.info().startInstant().map { start -> start.toEpochMilli() }.orElse(null) },
) {
    sealed interface Outcome {
        data object Stopped : Outcome

        data object Gone : Outcome

        data class Blocked(val reason: String) : Outcome
    }

    /** [path] is the directory of the workspace the plan found the process in. */
    fun stop(pid: Long, startMs: Long, path: String): Outcome {
        refusal(pid, startMs, path)?.let { return it }
        val handle = ProcessHandle.of(pid).orElse(null) ?: return Outcome.Gone
        val started = startMsOf(handle) ?: return Outcome.Blocked("its start time cannot be read, so it cannot be told from another process with the same pid")
        if (started != startMs) return Outcome.Gone
        if (busy(handle)) return Outcome.Blocked("busy: it used CPU in the last ${probeMs} ms")
        refusal(pid, startMs, path)?.let { return it }
        val children = handle.descendants().toList()
        handle.destroy()
        if (!exited(handle)) handle.destroyForcibly()
        children.forEach { it.destroy() }
        return if (exited(handle)) Outcome.Stopped else Outcome.Blocked("still running ${Duration.ofMillis(graceMs).seconds} s after it was told to stop")
    }

    /** Why the process must not be stopped right now, [Outcome.Gone] when it is not there, or null when the plan still holds. */
    private fun refusal(pid: Long, startMs: Long, path: String): Outcome? {
        val all = source.read()
        val target = all.firstOrNull { it.pid == pid && it.startMs == startMs } ?: return Outcome.Gone
        if (!target.kind.buildTool) return Outcome.Blocked("not a build tool any more: ${target.kind.name.lowercase()}")
        if (!ProcessAttribution.belongsTo(target, path)) return Outcome.Blocked("no longer works in the workspace")
        // A Kotlin daemon serves the builds of every workspace; the others only the one they work in.
        val building = all.any { it.kind == ProcessKind.GRADLE_CLIENT && (target.kind == ProcessKind.KOTLIN_DAEMON || ProcessAttribution.belongsTo(it, path)) }
        if (building) return Outcome.Blocked("a Gradle build is running")
        if (target.busy == true) return Outcome.Blocked("Gradle marks it busy")
        return null
    }

    // CPU time of the process and everything below it, twice: a test worker busy under an idle daemon counts as busy.
    private fun busy(handle: ProcessHandle): Boolean {
        val before = cpu(handle)
        sleep(probeMs)
        return cpu(handle) - before > IDLE_CPU_MS
    }

    private fun cpu(handle: ProcessHandle): Long = (listOf(handle) + handle.descendants().toList()).sumOf(ProcessCpu::ms)

    private fun exited(handle: ProcessHandle): Boolean = runCatching { handle.onExit().get(graceMs, TimeUnit.MILLISECONDS); true }.getOrDefault(false)

    private companion object {
        const val IDLE_CPU_MS = 100L
    }
}
