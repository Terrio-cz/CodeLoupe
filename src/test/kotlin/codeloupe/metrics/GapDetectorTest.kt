package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.metrics.TranscriptBuilder.Companion.args
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GapDetectorTest {
    private fun run(build: TranscriptBuilder.() -> Unit): Run {
        val file = TranscriptBuilder().prompt("go").apply(build).write(TestRepos.tmpDir("gaps").resolve("p").resolve("s.jsonl"))
        return TranscriptReader(Categorizer(Categorizer.DEFAULT_RULES)).read(TranscriptFile(file, "session", "main"))
    }

    private fun find(id: String, q: String) = Triple(id, "mcp__codeloupe__find", args("q" to q))

    @Test
    fun `a search for the same symbol right after a CodeLoupe call is a fallback`() {
        val gaps = GapDetector.detect(
            run {
                turn(tools = arrayOf(find("a", "OrderService.handle(_)")))
                result("a", "src/Order.kt:10-20  class OrderService")
                turn(tools = arrayOf(Triple("b", "Grep", args("pattern" to "handle"))))
                result("b", "src/Order.kt:12")
            },
        )
        assertEquals(listOf(Gap("2026-W41", "find", "find:overload", "fallback", "handle")), gaps)
    }

    @Test
    fun `reads of other code, or of the symbol three turns later, are not gaps`() {
        val gaps = GapDetector.detect(
            run {
                turn(tools = arrayOf(find("a", "OrderService")))
                result("a", "src/Order.kt:10-20  class OrderService")
                turn(tools = arrayOf(Triple("b", "Read", args("file_path" to "src/Payment.kt"))))
                result("b", "...")
                turn(tools = arrayOf(Triple("c", "Read", args("file_path" to "src/Other.kt"))))
                result("c", "...")
                turn(tools = arrayOf(Triple("d", "Grep", args("pattern" to "OrderService"))))
                result("d", "...")
            },
        )
        assertEquals(emptyList(), gaps)
    }

    @Test
    fun `a file read after asking about the file counts, a longer word containing the name does not`() {
        val run = run {
            turn(tools = arrayOf(Triple("a", "mcp__codeloupe__outline", args("target" to "src/main/Order.kt"))))
            result("a", "outline")
            turn(tools = arrayOf(Triple("b", "Read", args("file_path" to "C:/w/src/main/Order.kt"))))
            result("b", "...")
            turn(tools = arrayOf(Triple("c", "mcp__codeloupe__find", args("q" to "Order"))))
            result("c", "x")
            turn(tools = arrayOf(Triple("d", "Bash", args("command" to "rg Reorder src"))))
            result("d", "...")
        }
        assertEquals(listOf("outline:path:fallback:Order.kt"), GapDetector.detect(run).map { "${it.shape}:${it.kind}:${it.token}" })
    }

    @Test
    fun `empty, busy and candidate-only answers are gaps even without a fallback`() {
        val gaps = GapDetector.detect(
            run {
                turn(tools = arrayOf(find("a", "*Missing"), find("b", "Busy"), Triple("c", "mcp__codeloupe__usages", args("name" to "Account.rename"))))
                result("a", "no declaration \"*Missing\"")
                result("b", "busy: build running")
                result("c", "usages of Account.rename\n0 exact, 3 candidate")
            },
        )
        assertEquals(
            listOf("find:glob:empty", "find:name:busy", "usages:qualified:candidates"),
            gaps.map { "${it.shape}:${it.kind}" },
        )
    }

    @Test
    fun `the CLI inside a shell command is a CodeLoupe call too`() {
        val gaps = GapDetector.detect(
            run {
                turn(tools = arrayOf(Triple("a", "Bash", args("command" to "codeloupe symbol OrderService.total --root /w"))))
                result("a", "no declaration \"OrderService.total\"")
            },
        )
        assertEquals(listOf("symbol:qualified:empty"), gaps.map { "${it.shape}:${it.kind}" })
    }

    @Test
    fun `the report groups by week, shape and kind and counts every call`() {
        val runs = listOf(
            run {
                turn(tools = arrayOf(find("a", "Foo"), find("b", "Bar")))
                result("a", "no declaration \"Foo\"")
                result("b", "no declaration \"Bar\"")
            },
        )
        val report = GapReport.of(runs.asSequence())
        assertEquals(2, report.calls)
        assertEquals(listOf(GapRow("2026-W41", "find", "find:name", "empty", 2, listOf("Foo", "Bar"))), report.rows)
        assertContains(report.render(), "2 CodeLoupe calls in 1 runs, 2 gaps")
        assertContains(report.render(), "  2×  empty  find:name  (Foo, Bar)")
        assertTrue(GapReport.of(emptySequence()).render().startsWith("no gaps"))
    }
}
