package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.platform.BoundedRead
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserBoundsTest {
    private fun assistant(id: String, tool: String? = null): String {
        val content = if (tool == null) "[]" else """[{"type":"tool_use","id":"$tool","name":"Read","input":{}}]"""
        return """{"type":"assistant","timestamp":"2026-10-09T10:00:00Z","message":{"id":"$id","usage":{"input_tokens":1,"output_tokens":1},"content":$content}}"""
    }

    @Test
    fun `the snapshot of a long session stays bounded, and a message that repeats on the next line is still counted once`() {
        val parser = TranscriptParser("main")
        for (i in 1..6_000) parser.feed(assistant("msg-$i", tool = "call-$i"))
        val snapshot = parser.snapshot()
        assertTrue(snapshot.seen.size <= 5_000, "seen ${snapshot.seen.size}")
        assertTrue(snapshot.pending.size <= 1_000, "pending ${snapshot.pending.size}")
        assertEquals("call-6000", snapshot.pending.last().id, "the newest calls are the ones kept")

        val turns = parser.turns
        parser.feed(assistant("msg-6000"))
        assertEquals(turns, parser.turns, "the same message on a neighbouring line adds no turn")

        val resumed = TranscriptParser("main", snapshot)
        resumed.feed(assistant("msg-6000"))
        assertEquals(turns, resumed.turns)
    }

    @Test
    fun `a small file is read, a missing or large one is not`() {
        val dir = TestRepos.tmpDir("bounded")
        val small = Files.writeString(dir.resolve("a.meta.json"), """{"agentType":"x"}""")
        val large = Files.write(dir.resolve("b.meta.json"), ByteArray((BoundedRead.SMALL + 1).toInt()) { 'a'.code.toByte() })
        assertEquals("""{"agentType":"x"}""", BoundedRead.text(small))
        assertNull(BoundedRead.text(large))
        assertNull(BoundedRead.text(dir.resolve("none.json")))
    }
}
