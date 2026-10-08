package codeloupe.hooks

import codeloupe.TestRepos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWeightsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = TestRepos.tmpDir("weight").resolve("session.jsonl")
    private val transcript = SyntheticTranscript(file)
    private val warnAt = listOf(100_000, 150_000)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `the context is that of the last turn and the heavy results are those carried longest`() {
        val weights = SessionWeights(scope)
        transcript.turn(20_000, "Read", resultChars = 8_000)
        transcript.turn(30_000, "Bash", resultChars = 40_000)
        transcript.turn(60_000, "Grep", resultChars = 400)
        transcript.turn(95_000, "Read", resultChars = 60_000)
        val weight = weights.weigh(file, warnAt, top = 2)!!
        assertEquals(95_000, weight.contextTokens)
        assertEquals(4, weight.turns)
        assertEquals(0, weight.level)
        assertTrue(weight.complete)
        // carried = characters x turns that read them, the next one included: Bash 40 000 x 3, Read of turn 4 60 000 x 1, Read of turn 1 8 000 x 4.
        assertEquals(listOf("Bash" to 2, "Read" to 4), weight.heavy.map { it.tool to it.turn })
        assertEquals(10_000, weight.heavy.first().tokens)
    }

    @Test
    fun `each call reads only what was appended`() {
        val weights = SessionWeights(scope)
        transcript.turn(90_000)
        assertEquals(0, weights.weigh(file, warnAt, 3)!!.level)
        transcript.turn(101_000)
        val second = weights.weigh(file, warnAt, 3)!!
        assertEquals(1, second.level)
        assertEquals(2, second.turns)
        transcript.turn(160_000)
        assertEquals(2, weights.weigh(file, warnAt, 3)!!.level)
        transcript.turn(40_000)
        assertEquals(0, weights.weigh(file, warnAt, 3)!!.level, "after /compact the size is smaller again")
    }

    @Test
    fun `a last line the writer has not finished waits for the next call`() {
        val weights = SessionWeights(scope)
        transcript.turn(50_000)
        transcript.append("""{"type":"assistant","message":{"id":"m9","usage":{"input_tokens":3,"cache_read_input_tokens":""", terminated = false)
        assertEquals(50_000, weights.weigh(file, warnAt, 3)!!.contextTokens)
        transcript.append("""999997,"cache_creation_input_tokens":0,"output_tokens":1},"content":[]}}""")
        assertEquals(1_000_000, weights.weigh(file, warnAt, 3)!!.contextTokens)
    }

    @Test
    fun `a transcript that was rewritten starts over, and a missing one is no weight`() {
        val weights = SessionWeights(scope)
        transcript.turn(120_000, resultChars = 5_000)
        transcript.turn(130_000)
        assertEquals(1, weights.weigh(file, warnAt, 3)!!.level)
        transcript.rewrite()
        transcript.turn(10_000)
        val weight = weights.weigh(file, warnAt, 3)!!
        assertEquals(1, weight.turns)
        assertEquals(10_000, weight.contextTokens)
        assertNull(weights.weigh(file.resolveSibling("none.jsonl"), warnAt, 3))
    }

    @Test
    fun `a transcript far ahead of what was read is answered from its tail and read in the background`() {
        val weights = SessionWeights(scope, catchUpBytes = 20_000)
        repeat(40) { transcript.turn(2_500L * (it + 1), resultChars = 300) }
        val first = weights.weigh(file, warnAt, 3)!!
        assertFalse(first.complete)
        assertEquals(100_000, first.contextTokens)
        assertEquals(1, first.level)
        assertTrue(first.heavy.isEmpty())
        val deadline = System.currentTimeMillis() + 10_000
        var next = weights.weigh(file, warnAt, 3)!!
        while (!next.complete && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            next = weights.weigh(file, warnAt, 3)!!
        }
        assertTrue(next.complete)
        assertEquals(40, next.turns)
        assertEquals(3, next.heavy.size)
    }

    @Test
    fun `the advisory names tools and turns, never text`() {
        val weights = SessionWeights(scope)
        transcript.turn(30_000, "Read", resultChars = 200_000)
        transcript.turn(60_000, "Bash", resultChars = 100_000)
        transcript.turn(140_000, "Grep", resultChars = 10)
        val line = weights.weigh(file, warnAt, 3)!!.advisory()
        assertContains(line, "~140k tokens")
        assertContains(line, "Read in turn 1, Bash in turn 2")
        assertContains(line, "/compact")
        assertFalse("rrrr" in line)
        assertNotNull(Regex("""\d+ % of it in""").find(line))
        val other = SyntheticTranscript(file.resolveSibling("other.jsonl"))
        repeat(3) { other.turn(100_000L * (it + 1), "Bash", resultChars = 400) }
        assertContains(weights.weigh(other.path, warnAt, 3)!!.advisory(), "only")
    }

    @Test
    fun `a ten megabyte transcript is followed in a few milliseconds per turn`() {
        val weights = SessionWeights(scope)
        repeat(1_500) { transcript.turn(50_000L + it * 10, resultChars = 2_000, filler = 4_500) }
        assertTrue(Files.size(file) > 10_000_000, "${Files.size(file)} bytes")
        var weight = weights.weigh(file, warnAt, 3)!!
        val deadline = System.currentTimeMillis() + 60_000
        while (!weight.complete && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            weight = weights.weigh(file, warnAt, 3)!!
        }
        assertTrue(weight.complete)
        val millis = (1..20).map {
            transcript.turn(66_000L + it, "Bash", resultChars = 3_000)
            val started = System.nanoTime()
            weights.weigh(file, warnAt, 3)
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        println("weigh of an appended turn on ${Files.size(file) / 1_000_000} MB: median ${millis[10]} ms, max ${millis.last()} ms")
        assertTrue(millis[10] < 100, "median ${millis[10]} ms")
    }
}
