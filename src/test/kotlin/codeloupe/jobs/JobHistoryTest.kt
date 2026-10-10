package codeloupe.jobs

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.platform.Sqlite
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CL-209: the history of jobs survives a daemon that ends in any way, an update, and lasts seven days. */
class JobHistoryTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun finished(id: String, endedAt: Instant, log: Path) =
        JobStoreCrash.record(id, JobStatus.DONE, endedAt.minusSeconds(60), log.toString()).copy(endedAt = endedAt.toString(), exit = 0)

    @Test
    fun `finished jobs are listed again after a daemon ended without closing its database`() {
        val home = TestRepos.tmpDir("jobs-history-crash")
        val process = ProcessBuilder(JobStoreCrash.command(home.resolve("jobs.db"), 6)).inheritIO().start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the writer ended")
        assertTrue(Files.exists(home.resolve("jobs.db-wal")), "the records are in the write-ahead log only, as after a forced stop")

        JobStore(home.resolve("jobs.db")).use { store ->
            assertEquals(listOf("J5", "J4", "J3", "J2", "J1", "J0"), store.recent(50).map { it.id })
            assertTrue(store.recent(50).all { it.status == JobStatus.DONE && it.exit == 0 })
            assertEquals(6, store.count())
        }
    }

    @Test
    fun `a job that ran in one daemon is listed by the next one on the same home`() {
        val home = TestRepos.tmpDir("jobs-history-restart")
        val work = TestRepos.tmpDir("jobs-history-work")
        val port = ServerSocket(0).use { it.localPort }
        val first = Daemon.start(Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null))
        val id = try {
            val accepted = runBlocking { first.jobs.submit(JobRequest(FakeJob.command("print=hello"), work.toString())) } as Submission.Accepted
            val chain = runBlocking { first.jobs.await(accepted.job.id, 60_000) }
            assertEquals(JobStatus.DONE, chain.last().status)
            accepted.job.id
        } finally {
            first.stop()
        }

        val second = Daemon.start(Config(home, ServerSocket(0).use { it.localPort }, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null))
        try {
            assertEquals(listOf(id), second.jobs.list(50).map { it.id })
            assertEquals(JobStatus.DONE, second.jobs.get(id)?.status)
            assertTrue(Files.readString(Path.of(second.jobs.get(id)!!.log)).contains("hello"))
            assertTrue(Files.readString(home.resolve("daemon.log")).contains("jobs: 1 records kept"))
        } finally {
            second.stop()
        }
    }

    @Test
    fun `records written by the previous release are read by this one, one it cannot read does not hide the others`() {
        val home = TestRepos.tmpDir("jobs-history-update")
        // A record as 0.1.0 wrote it, with fields this version does not know and without the optional ones.
        val old = """{"id":"J261009-181644-23ej","rootId":"J261009-181644-23ej","command":"node run/terrio.mjs help","cwd":"C:/w","status":"done","exit":0,
            |"createdAt":"2026-10-09T18:16:44.159Z","endedAt":"2026-10-09T18:25:29.050Z","durationMs":524891,"log":"J261009-181644-23ej.log","wakeOn":"always","retired":true}""".trimMargin()
        Sqlite.open(home.resolve("jobs.db"), listOf("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL, record TEXT NOT NULL)")).use { db ->
            db.prepareStatement("INSERT INTO jobs(id, status, record) VALUES (?, ?, ?)").use { s ->
                listOf(Triple("J261009-181644-23ej", "done", old), Triple("Jnewer", "done", "{\"id\":\"Jnewer\",\"status\":\"from the future\"}")).forEach { (id, status, record) ->
                    s.setString(1, id)
                    s.setString(2, status)
                    s.setString(3, record)
                    s.executeUpdate()
                }
            }
        }

        JobStore(home.resolve("jobs.db"), clock).use { store ->
            assertEquals(listOf("J261009-181644-23ej"), store.recent(10).map { it.id })
            assertEquals(JobStatus.DONE, store.get("J261009-181644-23ej")?.status)
            assertEquals(2, store.count(), "the unreadable record stays for a newer version")
        }
    }

    @Test
    fun `a finished job stays for seven days however many newer ones there are, and goes with its log after that`() {
        val home = TestRepos.tmpDir("jobs-history-retention")
        val logs = Files.createDirectories(home.resolve("jobs"))
        fun job(id: String, age: Duration) = finished(id, now.minus(age), Files.writeString(logs.resolve("$id.log"), id))
        JobStore(home.resolve("jobs.db"), clock, keep = 3).use { store ->
            store.put(job("old", Duration.ofDays(8)))
            store.put(job("week", Duration.ofDays(6).plusHours(23)))
            store.put(job("recent1", Duration.ofHours(30)))
            store.put(job("recent2", Duration.ofHours(2)))
            store.put(job("recent3", Duration.ofHours(1)))
            store.put(job("recent4", Duration.ofMinutes(5)))
            store.put(job("recent5", Duration.ofMinutes(1)))
        }

        JobStore(home.resolve("jobs.db"), clock, keep = 3).use { store ->
            assertEquals(listOf("recent5", "recent4", "recent3", "recent2", "recent1", "week"), store.recent(50).map { it.id }, "pruned when the database is opened")
            assertNotNull(store.get("week"))
            assertFalse(Files.exists(logs.resolve("old.log")))
            assertTrue(Files.exists(logs.resolve("week.log")))
        }
    }

    @Test
    fun `a job still queued or running is never pruned, whatever its age`() {
        val home = TestRepos.tmpDir("jobs-history-live")
        JobStore(home.resolve("jobs.db"), clock, keep = 1).use { store ->
            store.put(JobStoreCrash.record("ancient", JobStatus.RUNNING, now.minus(Duration.ofDays(30))))
            store.put(finished("a", now.minusSeconds(10), home.resolve("a.log")))
            store.put(finished("b", now.minusSeconds(5), home.resolve("b.log")))
        }
        JobStore(home.resolve("jobs.db"), clock, keep = 1).use { store -> assertEquals(listOf("b", "a", "ancient"), store.recent(10).map { it.id }) }
    }

    @Test
    fun `the daemon says when job logs lie around whose records are gone`() {
        val home = TestRepos.tmpDir("jobs-history-lost")
        Files.writeString(Files.createDirectories(home.resolve("jobs")).resolve("J261009-181644-23ej.log"), "earlier output")
        val daemon = Daemon.start(Config(home, ServerSocket(0).use { it.localPort }, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null))
        try {
            val log = Files.readString(home.resolve("daemon.log"))
            assertTrue(log.contains("jobs: 0 records kept"))
            assertTrue(log.contains("1 job logs lie in") && log.contains("removed outside the daemon"), log)
        } finally {
            daemon.stop()
        }
    }
}
