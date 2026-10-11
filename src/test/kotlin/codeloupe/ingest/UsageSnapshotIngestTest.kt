package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.accounts.ClaudeAccount
import codeloupe.metrics.AccountIdentity
import codeloupe.metrics.TranscriptBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `/usage` reports and the account of every transcript the ingest stores (CL-226). */
class UsageSnapshotIngestTest {
    private val root = TestRepos.tmpDir("usage-snap")
    private val configA = root.resolve("cfg-a")
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val everything = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli() to Long.MAX_VALUE

    private fun account(id: String, configDir: Path = configA) = ClaudeAccount(id, id.uppercase(), configDir, implicit = false)

    private fun rig(vararg accounts: ClaudeAccount) = IngestRig(root = root, project = configA.resolve("projects").resolve("proj"), accounts = accounts.toList())

    private fun all(rig: IngestRig, who: LimitSnapshots.Who = LimitSnapshots.Who.All) = LimitSnapshots(rig.db).between(everything.first, everything.second, who, 1000)

    private fun reports() = TranscriptBuilder(start).prompt("p").turn().usageReport(session = 12, weekly = 40, fable = 7, severity = "warning")

    @Test
    fun `each limit of a report becomes a snapshot with its percent, reset, severity and the account of the config directory`() {
        rig(account("a")).use { rig ->
            reports().turn().usageReport(session = 13, weekly = 41, fable = 8).write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            val rows = all(rig)
            assertEquals(6, rows.size, "two reports of three meters")
            val first = rows.filter { it.at == rows.first().at }
            assertEquals(listOf("session", "weekly_all", "weekly_scoped"), first.map { it.kind })
            assertEquals(listOf(12.0, 40.0, 7.0), first.map { it.percent })
            assertEquals(listOf(null, null, "Fable"), first.map { it.scope })
            assertEquals(listOf("2026-10-08T13:00:00.000Z", "2026-10-11T13:00:00.000Z", null), first.map { it.resetsAt })
            assertTrue(first.all { it.severity == "warning" && it.account == "a" && it.session == "s1" && it.identity == null })
            assertEquals(listOf(false, true, false), first.map { it.active })
        }
    }

    @Test
    fun `a session that only ran usage has no run but its snapshots and its account are stored`() {
        rig(account("a")).use { rig ->
            TranscriptBuilder(start).usageReport(session = 1, weekly = 2, fable = 3).write(rig.project.resolve("usage-only.jsonl"))
            rig.ingest.passNow()

            assertTrue(rig.runs().isEmpty())
            assertEquals(3, all(rig).size)
            assertEquals("a", rig.db.reader.createStatement().use { s -> s.executeQuery("SELECT account FROM files").use { it.next(); it.getString(1) } }, "the session is stamped even without a run")
        }
    }

    @Test
    fun `reading a transcript again, after a growth or a full reread, stores no snapshot twice and loses none`() {
        rig(account("a")).use { rig ->
            val file = reports().write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            Files.writeString(file, TranscriptBuilder(start.plusSeconds(60)).usageReport(session = 20, weekly = 50, fable = 9).text(), StandardOpenOption.APPEND)
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            rig.ingest.passNow()
            assertEquals(6, all(rig).size)

            rig.db.clear()
            assertEquals(6, all(rig).size, "clearing what is read for a new parser keeps the snapshots")
        }
    }

    @Test
    fun `snapshots outlive the transcript they came from`() {
        rig(account("a")).use { rig ->
            val file = reports().write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            Files.delete(file)
            rig.ingest.passNow()
            assertEquals(3, all(rig).size)
        }
    }

    @Test
    fun `without an account that holds the file, or with two on one directory, the account stays undetermined`() {
        rig(account("b", root.resolve("elsewhere"))).use { rig ->
            reports().write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            assertTrue(all(rig).all { it.account == null }, "no listed account has the file")
            assertEquals(3, all(rig, LimitSnapshots.Who.Unknown).size)
        }
        val shared = TestRepos.tmpDir("usage-snap-shared")
        IngestRig(root = shared, project = shared.resolve("cfg").resolve("projects").resolve("proj"), accounts = listOf(account("a", shared.resolve("cfg")), account("b", shared.resolve("cfg")))).use { rig ->
            reports().write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            assertTrue(all(rig).all { it.account == null }, "two accounts on one directory prove nothing about either")
        }
    }

    @Test
    fun `the account the transcript names itself is kept as a fingerprint, also for snapshots written before it`() {
        rig(account("a")).use { rig ->
            val file = reports().write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            assertTrue(all(rig).all { it.identity == null })

            val uuid = "0b9a6c1e-1111-4222-8333-444455556666"
            Files.writeString(file, TranscriptBuilder(start.plusSeconds(60)).bridgeSession(uuid).text(), StandardOpenOption.APPEND)
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            rig.ingest.passNow()

            val fingerprint = AccountIdentity.fingerprint(uuid)
            assertTrue(all(rig).all { it.identity == fingerprint })
            assertFalse(uuid in all(rig).toString())
            assertEquals(12, fingerprint.length)
            assertEquals(fingerprint, rig.db.reader.createStatement().use { s -> s.executeQuery("SELECT identity FROM files").use { it.next(); it.getString(1) } })
        }
    }

    @Test
    fun `a report without limits stores nothing, and the series can be read per account and as the latest of each meter`() {
        rig(account("a")).use { rig ->
            TranscriptBuilder(start).usageReport(null, null, null, limits = false).usageReport(session = 5, weekly = 6, fable = 7).usageReport(session = 8, weekly = 9, fable = 10).write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            val snapshots = LimitSnapshots(rig.db)

            assertEquals(6, all(rig).size)
            assertEquals(6, all(rig, LimitSnapshots.Who.Account("a")).size)
            assertTrue(all(rig, LimitSnapshots.Who.Account("b")).isEmpty())
            assertEquals(listOf(8.0, 9.0, 10.0), snapshots.latest(LimitSnapshots.Who.All).map { it.percent }, "the newest report, ordered session, weekly, weekly per model")
            assertEquals(2, snapshots.between(everything.first, everything.second, LimitSnapshots.Who.All, 2).size)
            assertEquals(listOf(9.0, 10.0), snapshots.between(everything.first, everything.second, LimitSnapshots.Who.All, 2).map { it.percent }, "a cut series keeps the newest")
        }
    }

    @Test
    fun `the csv export quotes what needs it and leaves unknown cells empty`() {
        val row = LimitSnapshot("2026-10-08T10:00:01.000Z", "weekly_scoped", "weekly", "Fable, big", 7.0, null, "normal", false, "s1", null, null)
        val lines = LimitCsv.render(listOf(row)).lines()
        assertEquals("at,kind,group,scope,percent,resetsAt,severity,active,session,account,identity", lines[0])
        assertEquals("2026-10-08T10:00:01.000Z,weekly_scoped,weekly,\"Fable, big\",7.0,,normal,false,s1,,", lines[1])
    }
}
