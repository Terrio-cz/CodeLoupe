package codeloupe.metrics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The expected figures are what `run/codemetrics.mjs collect` printed and wrote for the same two transcripts, so the
 * wiki's claim that both tools agree is held by a test.
 */
class StartContextTest {
    private val categorizer = Categorizer(Categorizer.DEFAULT_RULES)

    private fun resource(name: String): Path = Path.of(javaClass.getResource("/metrics/$name")!!.toURI())

    private fun run(name: String): Run = TranscriptReader(categorizer).read(TranscriptFile(resource(name), "session", "main"))

    private fun summaries() = listOf("start-a.jsonl", "start-b.jsonl").map { RunSummary.of(run(it)) }

    @Test
    fun `a run keeps what its transcript held before the first turn, sized as JSON text`() {
        val a = assertNotNull(run("start-a.jsonl").startCtx)
        assertEquals(5500, a.s, "input + cache read + cache write of the first turn")
        assertEquals(6500, a.cost, "turn 1 paid 500 + 1250 + 4000 + 200, plus one read of 5500 on the one later turn")
        assertEquals(
            mapOf("body" to 49L, "skill_listing" to 49L, "hook_additional_context" to 10L, "deferred_tools_delta" to 13L, "date" to 85L, "instructions" to 20L, "prompt" to 14L),
            a.chars,
        )
        val b = assertNotNull(run("start-b.jsonl").startCtx)
        assertEquals(4300, b.s)
        assertEquals(mapOf("body" to 13L, "skill_listing" to 14L, "mcp_instructions_delta" to 23L, "environment" to 41L, "prompt" to 16L), b.chars)
    }

    @Test
    fun `a transcript without a counted turn has no start context`() {
        val file = TranscriptBuilder().prompt("hello").write(Files.createTempDirectory("start-none").resolve("s.jsonl"))
        assertNull(TranscriptReader(categorizer).read(TranscriptFile(file, "session", "main")).startCtx)
    }

    @Test
    fun `the role figures and the summary line match the workspace script`() {
        val aggregate = Aggregator.aggregate(summaries()).getValue("main")
        val start = assertNotNull(aggregate.start)
        assertEquals(2, start.runs)
        assertEquals(4900, start.s)
        assertEquals(12660, start.costSum)
        assertEquals(96.0, start.startOfRolePct)
        assertEquals(96.02, start.startPct)
        assertEquals(
            mapOf("body" to 10L, "claudeMd" to 3L, "prompt" to 5L, "skills" to 10L, "agentList" to 0L, "deferred" to 2L, "mcpInstr" to 4L, "hook" to 2L, "misc" to 20L, "rest" to 4845L),
            start.tokens,
        )
        assertEquals(
            mapOf("body" to 0.2, "claudeMd" to 0.1, "prompt" to 0.1, "skills" to 0.2, "agentList" to 0.0, "deferred" to 0.0, "mcpInstr" to 0.1, "hook" to 0.0, "misc" to 0.4, "rest" to 98.9),
            start.sharePct,
        )
        assertEquals(
            "   start: S med 4.9k = rest 4.8k  misc 20  body 10  skills 10  prompt 5  mcpInstr 4  claudeMd 3  deferred 2  hook 2  | 96% of the role's cost, 96.02% of all",
            StartRender.line(start),
        )
        assertEquals(StartRender.line(start), MetricsRender.summary(mapOf("main" to aggregate)).lines()[1])
    }

    @Test
    fun `compare prints the change of the start context between two reports`() {
        val before = assertNotNull(Aggregator.aggregate(summaries()).getValue("main").start)
        val after = before.copy(s = 3920, startOfRolePct = 91.2, startPct = 90.0, sharePct = before.sharePct + ("misc" to 0.3) + ("skills" to 1.0))
        assertEquals(
            listOf(
                "   start S 4.9k→3.9k (-20%)  share of role cost 96%→91.2% (-4.8%)  of all 96.02%→90% (-6%)",
                "   share of S by source: skills 0.2%→1% (+0.8%)  rest 98.9%→98.9% (+0%)",
            ),
            StartRender.compare(before, after),
        )
        assertEquals(emptyList(), StartRender.compare(before, null))
    }

    @Test
    fun `a parser resumed from its snapshot goes on with the same start context`() {
        val lines = Files.readAllLines(resource("start-a.jsonl"))
        val whole = TranscriptParser("main").also { p -> lines.forEach(p::feed) }
        val first = TranscriptParser("main").also { p -> lines.take(4).forEach(p::feed) }
        val resumed = TranscriptParser("main", first.snapshot()).also { p -> lines.drop(4).forEach(p::feed) }
        assertEquals(whole.startContext(), resumed.startContext())
        assertNotNull(resumed.startContext())
    }
}
