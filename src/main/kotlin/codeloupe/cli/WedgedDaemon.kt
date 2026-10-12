package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.daemon.DaemonInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The daemon `daemon.json` names, alive while its port gives no answer: one that ran out of heap holds the port with its
 * server threads gone (CL-238), and a new daemon cannot bind it. Only a process that is that daemon counts: it started just
 * before the `startedAt` it wrote, and its command line names CodeLoupe, so a pid the system gave to another process after a
 * crash is never taken for it.
 */
class WedgedDaemon(val pid: Long, val port: Int, private val handle: ProcessHandle?) {
    val message: String get() = "the daemon (pid $pid) is alive but does not answer on 127.0.0.1:$port: it is wedged; `codeloupe start` ends it and starts a new one"

    /** Ends the process, first giving one that is shutting down [graceMs] to end by itself; true once it is gone. */
    fun end(graceMs: Long = GRACE_MS): Boolean {
        val process = handle ?: return true
        if (waitExit(process, graceMs)) return true
        process.destroyForcibly()
        return waitExit(process, KILL_WAIT_MS)
    }

    private fun waitExit(process: ProcessHandle, ms: Long): Boolean =
        runCatching { process.onExit().get(ms, TimeUnit.MILLISECONDS); true }.getOrDefault(!process.isAlive)

    /** What is known of a process: alive, when it started and its command line (null where the system does not tell). */
    class Seen(val alive: Boolean, val start: Instant?, val commandLine: String?)

    companion object {
        private const val GRACE_MS = 5_000L
        private const val KILL_WAIT_MS = 10_000L

        /** A daemon starts listening and writes `daemon.json` within this time of its process starting. */
        private val START_WINDOW: Duration = Duration.ofMinutes(5)
        private val CLOCK_SLACK: Duration = Duration.ofSeconds(2)

        /**
         * The daemon `<home>/daemon.json` names for [port] when its process is alive; the caller has seen the port not answer.
         * Null when there is no such file, the process is gone, or it is not the daemon that wrote the file.
         */
        fun find(home: Path, port: Int, seen: (Long) -> Seen? = ::seen): WedgedDaemon? {
            val info = runCatching { JsonFormat.json.decodeFromString(DaemonInfo.serializer(), Files.readString(home.resolve("daemon.json"))) }.getOrNull() ?: return null
            if (info.port != port || info.pid == ProcessHandle.current().pid()) return null
            val process = seen(info.pid)?.takeIf { it.alive } ?: return null
            val startedAt = runCatching { Instant.parse(info.startedAt) }.getOrNull() ?: return null
            if (!isThatDaemon(process, startedAt)) return null
            return WedgedDaemon(info.pid, port, ProcessHandle.of(info.pid).orElse(null))
        }

        private fun isThatDaemon(process: Seen, startedAt: Instant): Boolean {
            val start = process.start
            if (start != null) return !start.isAfter(startedAt.plus(CLOCK_SLACK)) && start.isAfter(startedAt.minus(START_WINDOW))
            // No start time (a process of another user): its command line must name us.
            return process.commandLine?.contains("codeloupe", ignoreCase = true) == true
        }

        private fun seen(pid: Long): Seen? = ProcessHandle.of(pid).orElse(null)?.let { handle ->
            val info = handle.info()
            Seen(handle.isAlive, info.startInstant().orElse(null), info.commandLine().orElse(null))
        }
    }
}
