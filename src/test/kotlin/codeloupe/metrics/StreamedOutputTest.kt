package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.metrics.TranscriptBuilder.Companion.args
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Claude Code writes a streamed message on several lines; the first carries a few output tokens, the last the final count (CL-206). */
class StreamedOutputTest {
    private fun lines(builder: TranscriptBuilder): List<String> =
        Files.readAllLines(builder.write(TestRepos.tmpDir("streamed").resolve("s.jsonl")))

    private fun transcript() = TranscriptBuilder()
        .prompt("work on TER-1")
        .streamed(listOf(8, 8, 457), tools = arrayOf(Triple("a", "Read", args("file_path" to "A.kt"))))
        .result("a", "x".repeat(40))
        .streamed(listOf(5, 266))
        .turn(output = 40)
        .streamed(listOf(90, 30))

    @Test
    fun `output of a streamed message is its final count, everything else is counted once`() {
        val parser = TranscriptParser("main")
        lines(transcript()).forEach(parser::feed)

        assertEquals(4, parser.turns)
        assertEquals(457L + 266 + 40 + 90, parser.usage.output, "the largest value of each message id, a lower later one changes nothing")
        assertEquals(40L, parser.usage.input)
        assertEquals(400L, parser.usage.cacheRead)
        assertEquals(120L, parser.usage.cw1h)
        assertEquals(80L, parser.usage.cw5m)
        val (results, usages) = parser.drain()
        assertEquals(1, results.size)
        assertEquals(parser.usage.weighted(), usages.sumOf { it.usage.weighted() }, "the lines together carry what the run holds")
    }

    @Test
    fun `only the first line of a message makes a turn, the later ones carry the output it grew by`() {
        val parser = TranscriptParser("main")
        lines(transcript()).forEach(parser::feed)
        val (_, usages) = parser.drain()

        val turns = usages.filterNot { it.continuation }
        assertEquals(4, turns.size)
        assertTrue(turns.all { it.usage.input == 10L })
        val grown = usages.filter { it.continuation }
        assertEquals(listOf(449L, 261L), grown.map { it.usage.output })
        assertTrue(grown.all { it.usage.input == 0L && it.usage.cacheRead == 0L && it.usage.cw1h == 0L && it.usage.cw5m == 0L })
        assertFalse(usages.last().continuation, "a later line that is not higher adds nothing")
    }

    @Test
    fun `a transcript read in pieces ends as if read at once, also when a message is cut between its lines`() {
        val all = lines(transcript())
        val whole = TranscriptParser("main").also { p -> all.forEach(p::feed) }

        for (cut in 1 until all.size) {
            val first = TranscriptParser("main").also { p -> all.take(cut).forEach(p::feed) }
            val resumed = TranscriptParser("main", first.snapshot()).also { p -> all.drop(cut).forEach(p::feed) }
            assertEquals(whole.usage, resumed.usage, "cut after line $cut")
            assertEquals(whole.turns, resumed.turns, "cut after line $cut")
        }
    }

    @Test
    fun `the run summary and the cost use the final output`() {
        val file = transcript().write(TestRepos.tmpDir("streamed").resolve("s.jsonl"))
        val run = TranscriptReader(Categorizer(Categorizer.DEFAULT_RULES)).read(TranscriptFile(file, "session", "main"))
        val summary = RunSummary.of(run)
        assertEquals(853L, summary.usage.output)
        assertEquals(40.0 + 400 * 0.1 + 120 * 2.0 + 80 * 1.25 + 853 * 5.0, summary.cost.toDouble(), 0.5)
    }
}
