package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.DesktopProfile
import codeloupe.accounts.SessionIdentities
import codeloupe.metrics.AccountIdentity
import codeloupe.metrics.TranscriptBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sessions are attributed to the account of the Claude Desktop instance that lists them (CL-231), ahead of identity and directory. */
class DesktopSessionIngestTest {
    private val root = TestRepos.tmpDir("desktop-ingest")
    private val shared = root.resolve(".claude")
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val everything = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli() to Long.MAX_VALUE
    private val stamps = SessionIdentities(root.resolve(SessionIdentities.FILE))
    private val fpA = AccountIdentity.fingerprint("uuid-a")
    private val desktopA = DesktopProfile(root.resolve("Claude-ucet2"))
    private val desktopB = DesktopProfile(root.resolve("Claude"))

    private fun a(desktop: DesktopProfile? = desktopA) = ClaudeAccount("a", "A", shared, implicit = false, identity = fpA, desktopDir = desktop?.dir)

    private fun b(desktop: DesktopProfile? = desktopB) = ClaudeAccount("b", "B", shared, implicit = false, desktopDir = desktop?.dir)

    private fun rig(vararg accounts: ClaudeAccount) = IngestRig(root = root, project = shared.resolve("projects").resolve("proj"), accounts = accounts.toList(), identities = stamps)

    private fun report(session: Int) = TranscriptBuilder(start).prompt("p").turn().usageReport(session = session, weekly = 40, fable = 7)

    private fun accountOfSession(rig: IngestRig, session: String): List<String?> = LimitSnapshots(rig.db)
        .between(everything.first, everything.second, LimitSnapshots.Who.All, 1000).filter { it.session == session }.map { it.account }.distinct()

    private fun fileAccount(rig: IngestRig, session: String): List<String?> = rig.db.reader.createStatement().use { s ->
        s.executeQuery("SELECT account FROM files WHERE session = '$session'").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }

    @Test
    fun `the desktop instance decides before the identity and the identity before the unknown`() {
        stamps.record("sb", fpA)
        stamps.record("sc", fpA)
        desktopA.session("sa")
        desktopB.session("sb")
        desktopB.write("local_broken.json", "{ damaged")
        rig(a(), b()).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            report(22).write(rig.project.resolve("sb.jsonl"))
            report(33).write(rig.project.resolve("sc.jsonl"))
            report(44).write(rig.project.resolve("sd.jsonl"))
            rig.ingest.passNow()

            assertEquals(listOf<String?>("a"), accountOfSession(rig, "sa"), "listed by the desktop of A")
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sb"), "listed by the desktop of B although its login is the one A names")
            assertEquals(listOf<String?>("a"), accountOfSession(rig, "sc"), "not listed: the identity A names")
            assertEquals(listOf<String?>(null), accountOfSession(rig, "sd"), "nothing says: two accounts share the directory, so unknown")
            assertEquals(listOf<String?>("b"), fileAccount(rig, "sb"))
            assertEquals(2, LimitSnapshots(rig.db).between(everything.first, everything.second, LimitSnapshots.Who.Account("a"), 1000).map { it.session }.distinct().size)
            assertTrue(LimitSnapshots(rig.db).between(everything.first, everything.second, LimitSnapshots.Who.Unknown, 1000).all { it.session == "sd" })
        }
    }

    @Test
    fun `a desktop directory beats the config directory the transcript lies in`() {
        val ownConfig = root.resolve("cfg-own")
        val own = ClaudeAccount("own", "Own", ownConfig, implicit = false)
        val other = ClaudeAccount("x", "X", root.resolve("cfg-x"), implicit = false, desktopDir = desktopB.dir)
        desktopB.session("s1")
        IngestRig(root = root, project = ownConfig.resolve("projects").resolve("proj"), accounts = listOf(own, other), identities = stamps).use { rig ->
            report(11).write(rig.project.resolve("s1.jsonl"))
            report(22).write(rig.project.resolve("s2.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>("x"), accountOfSession(rig, "s1"))
            assertEquals(listOf<String?>("own"), accountOfSession(rig, "s2"), "not listed: the directory of its own")
        }
    }

    @Test
    fun `subagents follow the session that owns them`() {
        desktopA.session("sa")
        rig(a(), b()).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            TranscriptBuilder(start).prompt("review").turn().write(rig.project.resolve("sa").resolve("subagents").resolve("agent-1.jsonl"))
            java.nio.file.Files.writeString(rig.project.resolve("sa").resolve("subagents").resolve("agent-1.meta.json"), """{"agentType": "reviewer", "description": "review"}""")
            rig.ingest.passNow()
            assertEquals(2, rig.scalar("SELECT count(*) FROM files WHERE session = 'sa' AND account = 'a'"))
        }
    }

    @Test
    fun `a session file that appears after the transcript was read moves the stored rows`() {
        rig(a(), b()).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>(null), accountOfSession(rig, "sa"))

            desktopB.session("sa")
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sa"), "no new bytes in the transcript, the stored rows were assigned")
            assertEquals(listOf<String?>("b"), fileAccount(rig, "sa"))

            desktopA.session("sa", name = "local_newer", mtimeMs = System.currentTimeMillis() + 60_000)
            rig.ingest.passNow()
            assertEquals(listOf<String?>("a"), accountOfSession(rig, "sa"), "the newer of two files wins")
        }
    }

    @Test
    fun `changing the desktop directory of an account in accounts json moves its sessions at the next pass`() {
        desktopA.session("sa")
        rig(a(), b()).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>("a"), accountOfSession(rig, "sa"))
        }
        rig(a(desktop = desktopB), b(desktop = desktopA)).use { rig ->
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sa"), "the two profiles swapped owners")
        }
        rig(a(desktop = null), b(desktop = null)).use { rig ->
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sa"), "an attribution already made is kept when the directory is no longer listed")
        }
    }

    @Test
    fun `an account that names the fingerprint does not pull a listed session back from its desktop account`() {
        stamps.record("sa", fpA)
        desktopB.session("sa")
        rig(a(), b()).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sa"))
            stamps.record("later", fpA)
            report(22).write(rig.project.resolve("later.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sa"), "a new stamp makes the identity pass run again, the desktop assignment stands")
            assertEquals(listOf<String?>("a"), accountOfSession(rig, "later"))
        }
    }

    private fun identities(rig: IngestRig, session: String): Set<String?> = rig.db.reader.createStatement().use { s ->
        s.executeQuery("SELECT identity FROM files WHERE session = '$session' UNION SELECT identity FROM usage_snapshots WHERE session = '$session'").use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
    }

    @Test
    fun `a corrected stamp replaces the identity stored before it`() {
        val fpB = AccountIdentity.fingerprint("uuid-b")
        stamps.record("sc", fpB)
        rig(a(), b().copy(identity = fpB)).use { rig ->
            report(33).write(rig.project.resolve("sc.jsonl"))
            rig.ingest.passNow()
            assertEquals(listOf<String?>("b"), accountOfSession(rig, "sc"))

            stamps.record("sc", fpA)
            rig.ingest.passNow()
            assertEquals(setOf<String?>(fpA), identities(rig, "sc"))
            assertEquals(listOf<String?>("a"), accountOfSession(rig, "sc"))
            assertEquals(listOf<String?>("a"), fileAccount(rig, "sc"))
        }
    }
}
