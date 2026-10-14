package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.accounts.ClaudeAccount
import codeloupe.metrics.TranscriptBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Token components per minute and model of every run, with its account, for the limit calibration (CL-226). */
class TokenMinutesIngestTest {
    private val root = TestRepos.tmpDir("token-minutes")
    private val config = root.resolve("cfg-a")
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val everything = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli() to Long.MAX_VALUE

    private fun rig() = IngestRig(root = root, project = config.resolve("projects").resolve("proj"), accounts = listOf(ClaudeAccount("a", "A", config, implicit = false)))

    private fun rows(rig: IngestRig, who: LimitSnapshots.Who = LimitSnapshots.Who.All) = TokenMinutes(rig.db).between(everything.first, everything.second, who, 1000)

    @Test
    fun `each minute keeps the tokens of each model apart, with thinking and the account`() {
        rig().use { rig ->
            TranscriptBuilder(start, model = "claude-opus-5-5").prompt("p")
                .turnOf("claude-opus-5-5", input = 10, output = 100, thinking = 40, cacheRead = 1000)
                .turnOf("claude-fable-5-1", input = 3, output = 50)
                .streamed(listOf(2, 9), input = 1)
                .later(120)
                .turnOf("claude-opus-5-5", output = 7)
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            val rows = rows(rig)
            assertEquals(listOf("2026-10-08T10:00:00.000Z" to "claude-fable-5-1", "2026-10-08T10:00:00.000Z" to "claude-opus-5-5", "2026-10-08T10:02:00.000Z" to "claude-opus-5-5"), rows.map { it.minute to it.model })
            val opus = rows[1]
            assertEquals(listOf(11L, 40L, 60L, 1100L, 109L, 40L), listOf(opus.input, opus.cw5m, opus.cw1h, opus.cacheRead, opus.output, opus.thinking), "the streamed turn adds its final output once")
            assertEquals(listOf(3L, 50L, 0L), listOf(rows[0].input, rows[0].output, rows[0].thinking))
            assertEquals(setOf(Triple<String?, String, String>("a", "s1", "session")), rows.map { Triple(it.account, it.session, it.kind) }.toSet())
            assertEquals(3, rows(rig, LimitSnapshots.Who.Account("a")).size)
            assertTrue(rows(rig, LimitSnapshots.Who.Unknown).isEmpty())
            assertEquals(rows.drop(1), TokenMinutes(rig.db).between(everything.first, everything.second, LimitSnapshots.Who.All, 2), "a cut page keeps the newest")
        }
    }

    @Test
    fun `the rows of a transcript read again are not counted twice`() {
        rig().use { rig ->
            val file = rig.project.resolve("s1.jsonl")
            TranscriptBuilder(start).prompt("p").turnOf("m", output = 5).turnOf("m", output = 6).write(file)
            rig.ingest.passNow()
            rig.ingest.passNow()
            assertEquals(11L, rows(rig).single().output, "a pass without new lines adds nothing")

            TranscriptBuilder(start).prompt("p").turnOf("m", output = 5).write(file)
            rig.ingest.passNow()
            assertEquals(5L, rows(rig).single().output, "a shorter transcript replaces what was read of the longer one")
        }
    }

    @Test
    fun `the csv has a header and a row per minute`() {
        val row = TokenMinute("2026-10-08T10:00:00Z", "s1", "subagent", "terrio-coder", "C--repo", "claude-opus-5-5", 1, 2, 3, 4, 5, 6, null, "abc")
        assertEquals(
            "minute,session,kind,role,project,model,input,cw5m,cw1h,cacheRead,output,thinking,account,identity\n2026-10-08T10:00:00Z,s1,subagent,terrio-coder,C--repo,claude-opus-5-5,1,2,3,4,5,6,,abc\n",
            TokenMinuteCsv.render(listOf(row)),
        )
    }
}
