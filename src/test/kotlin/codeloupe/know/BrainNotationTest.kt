package codeloupe.know

import kotlinx.coroutines.runBlocking
import codeloupe.TestRepos
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainNotationTest {
    private val text = BrainNotationTest::class.java.getResource("/know/brain-sample.md")!!.readText()
    private val parsed = BrainNotation.parse(text, "area:docker")
    private fun fact(start: String) = parsed.facts.first { it.text.startsWith(start) }

    @Test
    fun `lines are counted, headings and per-task state skipped, a wrapped fact joined`() {
        assertEquals(24, parsed.lines)
        assertEquals(17, parsed.facts.size)
        assertEquals(6, parsed.skipped, "headings, the boilerplate line, @ and % lines and a stray prose line")
        assertTrue(parsed.facts.none { it.text.startsWith("TER-51 importer") || it.text.startsWith("plan=ok") })
        assertContains(fact("a long fact").text, "that wraps onto an indented line")
        assertTrue(parsed.facts.all { it.scope == "area:docker" && !it.verify })
    }

    @Test
    fun `the prefix gives the kind and the plus lines follow the option`() {
        assertEquals("blocker", fact("never --rmi").kind)
        assertEquals("blocker", fact("P1 AccountHttp").kind)
        assertEquals("decision", fact("anti-join").kind)
        assertEquals("environment", fact("PASS 475").kind)
        assertEquals("environment", fact("shared stack").kind)
        assertEquals("other", fact("shared app image").kind)
        assertEquals("other", fact("node run/tool.mjs").kind)
        assertEquals("other", fact("coder: steps").kind)
        assertEquals("other", fact("2026-10-10 measured").kind)
        assertEquals("other", fact("run/tool.mjs now strips").kind)
        assertEquals("pattern", BrainNotation.parse(text, "area:x", FactKind.PATTERN).facts.first { it.text.startsWith("shared stack") }.kind)
    }

    @Test
    fun `a trailing stamp gives the time and the run and leaves the text`() {
        val stamped = fact("never --rmi")
        assertEquals("2026-09-23T13:16:09.000Z", stamped.createdAt)
        assertEquals("orch-0923", stamped.run)
        assertFalse("<!--" in stamped.text)
        assertNull(fact("shared stack").createdAt)
    }

    @Test
    fun `the first task id is the source, words like UTF-8 are not tasks`() {
        assertEquals("TER-167", fact("P1 AccountHttp").task)
        assertEquals("TER-51", fact("anti-join").task)
        assertEquals("CL-12", fact("a long fact").task)
        assertEquals("TER-677", fact("2026-10-10 measured").task)
        assertNull(fact("shared stack").task)
    }

    @Test
    fun `cited paths become file anchors, urls and elided paths do not`() {
        assertEquals(listOf("public-api/src/main/kotlin/demo/StatisticsRepository.kt"), fact("StatisticsRepository").anchors.map { it.path })
        assertEquals(listOf("docs/missing/Gone.md", "app/src/Main.kt"), fact("the docs say").anchors.map { it.path })
        assertTrue(fact("elided path").anchors.isEmpty())
        assertEquals(listOf("/work/b"), BrainNotation.parse("+ see x/y.kt", "repo") { "/work/b" }.facts.single().anchors.map { it.root })
    }

    @Test
    fun `a fact that says it is superseded is stored as such`() {
        assertTrue(fact("old way of working").superseded)
        assertFalse(fact("shared stack").superseded)
    }

    @Test
    fun `an import merges the repeated line into the older fact and flags the cited file that is gone`() {
        val dir = TestRepos.tmpDir("know")
        val checkout = TestRepos.tmpDir("checkout")
        val know = Know(dir.resolve("know.db"), AnchorChecker(NoIndex), defaultRoot = null)
        try {
            val results = parsed.facts.map { runBlocking { know.put(it.copy(root = checkout.toString())) } }
            assertEquals(16, results.count { it.action == "added" })
            assertEquals(1, results.count { it.action == "updated" })
            assertEquals(results.first { it.fact.text.startsWith("shared volume") }.fact.id, results.last { it.fact.text.startsWith("shared volume") }.fact.id)
            val old = results.first { it.fact.text.startsWith("old way") }.fact
            assertEquals(FactStatus.SUPERSEDED, old.status)
            assertEquals(emptyList(), runBlocking { know.search("working way new") }, "a superseded fact is not found")
        } finally {
            know.close()
            dir.toFile().deleteRecursively()
            checkout.toFile().deleteRecursively()
        }
    }
}
