package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.accounts.ClaudeAccount
import codeloupe.accounts.SessionIdentities
import codeloupe.metrics.AccountIdentity
import codeloupe.metrics.TranscriptBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two logins sharing the default config directory are told apart by the stamp the session-start hook wrote (CL-226). */
class SessionIdentityIngestTest {
    private val root = TestRepos.tmpDir("session-identity-ingest")
    private val shared = root.resolve(".claude")
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val everything = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli() to Long.MAX_VALUE
    private val stamps = SessionIdentities(root.resolve(SessionIdentities.FILE))
    private val fpA = AccountIdentity.fingerprint("uuid-a")
    private val fpB = AccountIdentity.fingerprint("uuid-b")

    private fun default() = ClaudeAccount("default", "Default account", shared, implicit = true)

    private fun rig(vararg accounts: ClaudeAccount) = IngestRig(root = root, project = shared.resolve("projects").resolve("proj"), accounts = accounts.toList().ifEmpty { listOf(default()) }, identities = stamps)

    private fun all(rig: IngestRig, who: LimitSnapshots.Who = LimitSnapshots.Who.All) = LimitSnapshots(rig.db).between(everything.first, everything.second, who, 1000)

    private fun report(session: Int = 10) = TranscriptBuilder(start).prompt("p").turn().usageReport(session = session, weekly = 40, fable = 7)

    private fun touch(file: Path) = Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

    @Test
    fun `sessions of two logins in one directory get separate anonymous accounts, and none counts as unknown`() {
        stamps.record("sa", fpA)
        stamps.record("sb", fpB)
        rig().use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            report(22).write(rig.project.resolve("sb.jsonl"))
            report(33).write(rig.project.resolve("sc.jsonl"))
            rig.ingest.passNow()

            val snapshots = LimitSnapshots(rig.db)
            assertEquals(setOf("identity-$fpA", "identity-$fpB", "default"), all(rig).map { it.account }.toSet())
            assertEquals(listOf(11.0), all(rig, LimitSnapshots.Who.Account("identity-$fpA")).filter { it.kind == "session" }.map { it.percent })
            assertEquals(listOf(22.0), all(rig, LimitSnapshots.Who.Account("identity-$fpB")).filter { it.kind == "session" }.map { it.percent })
            assertTrue(all(rig, LimitSnapshots.Who.Unknown).isEmpty(), "a session with a known identity is never unknown")
            assertEquals(listOf(11.0, 22.0, 33.0), snapshots.latest(LimitSnapshots.Who.All).filter { it.kind == "session" }.sortedBy { it.session }.map { it.percent }, "each account has its own newest report")
            assertEquals(setOf(fpA, fpB, null), all(rig).map { it.identity }.toSet())
            assertEquals(listOf("identity-$fpB"), snapshots.latest(LimitSnapshots.Who.Account("identity-$fpB")).map { it.account }.distinct(), "the latest of one anonymous account, as `--latest --account` asks")
        }
    }

    @Test
    fun `an account that names the fingerprint in accounts json owns the sessions of that login, the other one on the directory stays anonymous`() {
        stamps.record("sa", fpA)
        stamps.record("sb", fpB)
        val a = ClaudeAccount("a", "Account A", shared, implicit = false, identity = fpA)
        val b = ClaudeAccount("b", "Account B", shared, implicit = false)
        rig(a, b).use { rig ->
            report(11).write(rig.project.resolve("sa.jsonl"))
            report(22).write(rig.project.resolve("sb.jsonl"))
            rig.ingest.passNow()
            assertEquals(setOf("a", "identity-$fpB"), all(rig).map { it.account }.toSet())
        }
    }

    @Test
    fun `an account with a directory of its own keeps the sessions its location proves`() {
        val own = root.resolve("cfg-own")
        stamps.record("sa", fpA)
        val a = ClaudeAccount("own", "Own", own, implicit = false)
        IngestRig(root = root, project = own.resolve("projects").resolve("proj"), accounts = listOf(a), identities = stamps).use { rig ->
            report().write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(setOf("own"), all(rig).map { it.account }.toSet())
            assertEquals(setOf(fpA), all(rig).map { it.identity }.toSet())
        }
    }

    @Test
    fun `subagents of a stamped session carry its identity`() {
        stamps.record("sa", fpA)
        rig().use { rig ->
            report().write(rig.project.resolve("sa.jsonl"))
            val subagents = rig.project.resolve("sa").resolve("subagents")
            TranscriptBuilder(start).prompt("review it").turn().write(subagents.resolve("agent-1.jsonl"))
            Files.writeString(subagents.resolve("agent-1.meta.json"), """{"agentType": "reviewer", "description": "review"}""")
            rig.ingest.passNow()

            assertEquals(2, rig.scalar("SELECT count(*) FROM files WHERE identity = '$fpA' AND account = 'identity-$fpA'"), "the session and its subagent")
        }
    }

    @Test
    fun `a stamp that arrives after the transcript was read fills the identity and moves the account`() {
        rig().use { rig ->
            val file = report().write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(setOf("default"), all(rig).map { it.account }.toSet())
            assertTrue(all(rig).all { it.identity == null })

            stamps.record("sa", fpA)
            touch(file)
            rig.ingest.passNow()

            assertEquals(setOf("identity-$fpA"), all(rig).map { it.account }.toSet())
            assertEquals(setOf(fpA), all(rig).map { it.identity }.toSet())
            assertEquals(1, rig.scalar("SELECT count(*) FROM files WHERE identity = '$fpA' AND account = 'identity-$fpA'"))
        }
    }

    @Test
    fun `an unchanged transcript is stamped too, and naming the fingerprint later moves its snapshots to that account`() {
        rig().use { rig ->
            report().write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            stamps.record("sa", fpA)
            rig.ingest.passNow()
            assertEquals(setOf("identity-$fpA"), all(rig).map { it.account }.toSet(), "no new bytes in the transcript, the stored rows were stamped")
        }
        val named = ClaudeAccount("a", "Account A", shared, implicit = false, identity = fpA)
        rig(named).use { rig ->
            rig.ingest.passNow()
            assertEquals(setOf("a"), all(rig).map { it.account }.toSet())
        }
    }

    @Test
    fun `without stamps the account stays what the location says`() {
        rig().use { rig ->
            report().write(rig.project.resolve("sa.jsonl"))
            rig.ingest.passNow()
            assertEquals(setOf("default"), all(rig).map { it.account }.toSet())
        }
    }
}
