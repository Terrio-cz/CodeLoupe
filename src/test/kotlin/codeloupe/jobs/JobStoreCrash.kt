package codeloupe.jobs

import codeloupe.platform.JavaProcess
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Writes jobs to a `jobs.db` and ends the JVM at once, without closing the database: how Windows ends a daemon that is stopped by
 * force, so the records live only in the write-ahead log. Arguments: the database file, how many jobs.
 */
object JobStoreCrash {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = JobStore(Path.of(args[0]))
        repeat(args[1].toInt()) { i ->
            val queued = record("J$i", JobStatus.QUEUED)
            store.put(queued)
            store.put(queued.copy(status = JobStatus.RUNNING, startedAt = Instant.now().toString()))
            store.put(queued.copy(status = JobStatus.DONE, exit = 0, endedAt = Instant.now().toString(), durationMs = 5))
        }
        Runtime.getRuntime().halt(0)
    }

    fun command(file: Path, jobs: Int): List<String> =
        JavaProcess.command(JobStoreCrash::class.java.name, listOf("-Xmx64m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), listOf(file.toString(), jobs.toString()))

    fun record(id: String, status: JobStatus, createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS), log: String = "$id.log") = JobRecord(
        id = id, rootId = id, command = "node run/terrio.mjs help", cwd = "C:/w", status = status, createdAt = createdAt.toString(), log = log, wakeOn = Wake.ALWAYS,
    )
}
