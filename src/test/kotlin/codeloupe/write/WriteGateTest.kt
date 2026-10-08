package codeloupe.write

import codeloupe.TestRepos
import codeloupe.config.WriteConfig
import codeloupe.metrics.Run
import codeloupe.metrics.ToolCall
import codeloupe.metrics.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The gate of the `edit` tool: the card's rule, read from transcripts, and the modes that override it. */
class WriteGateTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun call(seq: Int, turn: Int, name: String, category: String, file: String? = null, partial: Boolean = false, input: JsonObject = JsonObject(emptyMap())) =
        ToolCall(seq, name, category, input, file, partial, null, turn, 100, false, 1, null, "", 0, 0)

    private fun run(vararg calls: ToolCall) =
        Run("r.jsonl", "subagent", "coder", null, null, "2026-10-01T00:00:00Z", "2026-10-01T01:00:00Z", 10, Usage(), 0, calls.toList())

    private fun edit(old: String, new: String) = buildJsonObject { put("old_string", old); put("new_string", new) }

    @Test
    fun `a whole-file read followed by an edit of that file counts, a partial read or another file does not`() {
        val counted = run(call(1, 1, "Read", "code_read", "A.kt"), call(2, 2, "Edit", "code_write", "A.kt"))
        assertEquals(1, ManualEdits.wholeFileReads(counted))
        assertEquals(0, ManualEdits.wholeFileReads(run(call(1, 1, "Read", "code_read", "A.kt", partial = true), call(2, 2, "Edit", "code_write", "A.kt"))))
        assertEquals(0, ManualEdits.wholeFileReads(run(call(1, 1, "Read", "code_read", "A.kt"), call(2, 2, "Edit", "code_write", "B.kt"))))
        assertEquals(0, ManualEdits.wholeFileReads(run(call(1, 1, "Read", "code_read", "A.kt"), call(2, 9, "Edit", "code_write", "A.kt"))), "too many turns later")
        assertEquals(0, ManualEdits.wholeFileReads(run(call(1, 1, "Read", "code_read", "A.kt"), call(2, 2, "Write", "code_write", "A.kt"))), "a new file is not an edit")
    }

    @Test
    fun `one identifier swapped by hand in three files is a hand rename`() {
        val swap = { file: String -> call(1, 1, "Edit", "code_write", file, input = edit("val total = compute(x)", "val sum = compute(x)")) }
        assertTrue(ManualEdits.renamedByHand(run(swap("A.kt"), swap("B.kt"), swap("C.kt"))))
        assertFalse(ManualEdits.renamedByHand(run(swap("A.kt"), swap("B.kt"))), "two files are a coincidence")
        assertFalse(ManualEdits.renamedByHand(run(*(1..3).map { call(it, 1, "Edit", "code_write", "F$it.kt", input = edit("a(1)", "b(2)")) }.toTypedArray())))
        val multi = call(1, 1, "MultiEdit", "code_write", "D.kt", input = buildJsonObject { put("edits", JsonArray(listOf(edit("fun total()", "fun sum()")))) })
        assertTrue(ManualEdits.renamedByHand(run(multi, call(2, 1, "Edit", "code_write", "E.kt", input = edit("total()", "sum()")), call(3, 1, "Edit", "code_write", "F.kt", input = edit("x.total", "x.sum")))))
    }

    @Test
    fun `auto opens on the thresholds of the rule, on and off decide alone`() {
        val cache = TestRepos.tmpDir("gate").resolve("gate.json")
        val reading = run(*(1..25).flatMap { listOf(call(it * 2, it, "Read", "code_read", "A$it.kt"), call(it * 2 + 1, it, "Edit", "code_write", "A$it.kt")) }.toTypedArray())
        val busy = WriteGate(WriteConfig(), cache, { now }) { sequenceOf(reading) }
        assertFalse(busy.open(), "nothing is known before the first look")
        assertTrue(busy.stale())
        val verdict = busy.refresh()
        assertTrue(busy.open())
        assertEquals(25, verdict.wholeFileReads)
        assertEquals(1, verdict.runs)
        assertTrue(WriteGate(WriteConfig(), cache, { now }) { emptySequence() }.open(), "the verdict is kept in a file")

        val quiet = WriteGate(WriteConfig(), TestRepos.tmpDir("gate").resolve("g.json"), { now }) { sequenceOf(run(call(1, 1, "Read", "code_read", "A.kt"))) }
        quiet.refresh()
        assertFalse(quiet.open())
        assertTrue(quiet.evaluate().render(WriteConfig.GateRule()).startsWith("write gate closed"))

        assertTrue(WriteGate(WriteConfig(mode = "on"), TestRepos.tmpDir("gate").resolve("x.json"), { now }) { emptySequence() }.open())
        val off = WriteGate(WriteConfig(mode = "off"), cache, { now }) { sequenceOf(reading) }
        off.refresh()
        assertFalse(off.open())
    }

    @Test
    fun `the write section of config parses with its defaults`() {
        val parsed = WriteConfig.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"write": {"mode": "on", "linkedWorktreesOnly": true, "deny": ["**/.env", " "], "gate": {"wholeFileReads": 5, "manualRenames": 1, "windowDays": 7}}}""") as JsonObject)
        assertEquals("on", parsed.mode)
        assertTrue(parsed.linkedWorktreesOnly)
        assertEquals(listOf("**/.env"), parsed.deny)
        assertEquals(WriteConfig.GateRule(windowDays = 7, wholeFileReads = 5, manualRenames = 1), parsed.gate)
        assertEquals(WriteConfig(), WriteConfig.parse(JsonObject(emptyMap())))
        assertEquals("auto", WriteConfig.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"write": {"mode": "maybe"}}""") as JsonObject).mode)
        assertTrue(JsonPrimitive(1).toString().isNotEmpty())
    }
}
