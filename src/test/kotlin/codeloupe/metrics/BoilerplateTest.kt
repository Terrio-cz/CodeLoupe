package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.metrics.TranscriptBuilder.Companion.args
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoilerplateTest {
    private val skeleton = """
        package com.example

        import com.example.A
        import com.example.B

        @Serializable
        data class Order(
            val id: String,
        )
    """.trimIndent() + "\n"

    private fun run(role: String = "coder", build: TranscriptBuilder.() -> Unit): Run {
        val file = TranscriptBuilder().prompt("go").apply(build).write(TestRepos.tmpDir("boiler").resolve("p").resolve("s.jsonl"))
        return TranscriptReader(Categorizer(Categorizer.DEFAULT_RULES)).read(TranscriptFile(file, "subagent", role))
    }

    private fun write(id: String, path: String, content: String) = Triple(id, "Write", args("file_path" to path, "content" to content))

    @Test
    fun `only files the tool reports as created are new, and only code files`() {
        val run = run {
            turn(tools = arrayOf(write("a", "src/main/Order.kt", skeleton), write("b", "src/main/Old.kt", skeleton), write("c", "notes.md", "# x\n")))
            result("a", "File created successfully at: src/main/Order.kt")
            result("b", "The file src/main/Old.kt has been updated successfully.")
            result("c", "File created successfully at: notes.md")
        }
        assertEquals(listOf("src/main/Order.kt"), BoilerplateAnalyzer.newFiles(run).map { it.path })
    }

    @Test
    fun `package, imports, annotations, headers, braces and blank lines are skeleton, bodies are not`() {
        val body = skeleton + "\nfun total(items: List<Int>): Int {\n    return items.sum()\n}\n"
        val run = run {
            turn(tools = arrayOf(write("a", "src/main/Order.kt", body)))
            result("a", "File created successfully at: src/main/Order.kt")
        }
        val share = BoilerplateAnalyzer.newFiles(run).single().share
        assertEquals(body.length.toLong(), share.chars)
        val content = "    val id: String,\n".length + "fun total(items: List<Int>): Int {\n".length + "    return items.sum()\n".length
        assertEquals(body.length.toLong() - content, share.boilerplateChars, "a constructor parameter, a signature and a statement are content")
        assertTrue(share.sharePct in 50.0..90.0, share.sharePct.toString())
    }

    @Test
    fun `the report splits test from main, names a decision and handles no data`() {
        val run = run {
            turn(tools = arrayOf(write("a", "src/test/kotlin/OrderTest.kt", skeleton), write("b", "src/main/kotlin/Order.kt", "fun a() = 1\nfun b() = 2\nfun c() = 3\n")))
            result("a", "File created successfully at: src/test/kotlin/OrderTest.kt")
            result("b", "File created successfully at: src/main/kotlin/Order.kt")
        }
        val report = BoilerplateReport.of(sequenceOf(run))
        assertEquals(setOf("test", "main"), report.byKind.keys)
        assertEquals(2, report.total.files)
        assertTrue(report.byKind.getValue("test").sharePct > 80.0)
        assertEquals(0.0, report.byKind.getValue("main").sharePct)
        assertTrue(report.decision.startsWith("go") || report.decision.startsWith("no-go"), report.decision)
        assertContains(report.render(), "decision: ")
        assertTrue(BoilerplateReport.of(emptySequence()).decision.startsWith("no data"))
    }
}
