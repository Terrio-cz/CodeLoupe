package codeloupe.know

import codeloupe.TestRepos
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowStoreTest {
    private val dir = TestRepos.tmpDir("know")
    private val file = dir.resolve("know.db")
    private var know = open()

    private fun open() = Know(file, AnchorChecker(NoIndex), defaultRoot = null, head = { "abc1234" }, now = clock())

    private fun clock(): () -> String {
        var n = 0
        return { "2026-10-10T10:00:%02d.000Z".format(n++) }
    }

    private fun put(text: String, scope: String = "area:docker", kind: String = "environment", task: String? = null, supersedes: List<String> = emptyList()) =
        runBlocking { know.put(FactDraft(scope, text, kind, task = task, supersedes = supersedes)) }

    private fun search(question: String, scope: String? = null, kind: String? = null, limit: Int = 5, all: Boolean = false) =
        runBlocking { know.search(question, scope, kind, limit, all) }

    @AfterTest
    fun cleanUp() {
        know.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a written fact is read back with its scope, kind, source and ids`() {
        val result = put("shared stack uses a fixed container name for postgres", scope = "area:docker", kind = "environment", task = "TER-12")
        assertEquals("added", result.action)
        val fact = know.get(result.fact.id)!!
        assertTrue(Regex("k\\d+").matches(fact.id))
        assertEquals(FactScope.AREA, fact.scope)
        assertEquals("docker", fact.target)
        assertEquals(FactKind.ENVIRONMENT, fact.kind)
        assertEquals(FactSource(task = "TER-12"), fact.source)
        assertEquals(FactStatus.ACTIVE, fact.status)
        assertEquals(fact, know.get(fact.id.removePrefix("k")), "the number alone names the fact too")
        assertNull(know.get("k999"))
    }

    @Test
    fun `a near-identical fact of the same scope updates the older one`() {
        val first = put("never remove the shared postgres volume, it holds the imported data", task = "TER-1").fact
        val again = put("Never remove the shared postgres volume: it holds the imported data!", task = "TER-2")
        assertEquals("updated", again.action)
        assertEquals(first.id, again.fact.id)
        assertEquals("Never remove the shared postgres volume: it holds the imported data!", know.get(first.id)!!.text)
        assertEquals(first.createdAt, again.fact.createdAt)
        assertNotEquals(first.updatedAt, again.fact.updatedAt)
        assertEquals("TER-2", again.fact.source.task)
        assertEquals(1, search("postgres volume").size)
    }

    @Test
    fun `the same words in another scope, or with other numbers, are another fact`() {
        val a = put("the test run needs 6 gigabytes of memory", scope = "area:gradle").fact
        assertNotEquals(a.id, put("the test run needs 6 gigabytes of memory", scope = "area:docker").fact.id)
        assertNotEquals(a.id, put("the test run needs 8 gigabytes of memory", scope = "area:gradle").fact.id)
        assertNotEquals(a.id, put("the test run needs 6 gigabytes of memory and a pinned java version", scope = "area:gradle").fact.id)
    }

    @Test
    fun `a superseded fact leaves the search unless asked for`() {
        val old = put("the staging app listens on port 8080", scope = "area:staging").fact
        val new = put("the staging app listens on port 9090 since the move", scope = "area:staging", supersedes = listOf(old.id))
        assertEquals(1, new.replaced)
        assertEquals(FactStatus.SUPERSEDED, know.get(old.id)!!.status)
        assertEquals(listOf(new.fact.id), search("staging app port").map { it.fact.id })
        assertEquals(setOf(old.id, new.fact.id), search("staging app port", all = true).map { it.fact.id }.toSet())
        assertContains(search("staging app port", all = true).first { it.fact.id == old.id }.line, "SUPERSEDED")
    }

    @Test
    fun `search ranks by relevance and filters by scope and kind`() {
        val retry = put("retries use exponential backoff capped at one minute", scope = "area:webhooks", kind = "decision").fact
        put("the retry button in the app repeats the last failed import", scope = "area:desktop", kind = "other")
        put("signing keys rotate every ninety days", scope = "area:secrets", kind = "decision")
        assertEquals(retry.id, search("retry exponential backoff").first().fact.id)
        assertEquals(listOf("area:desktop"), search("retry", scope = "area:desktop").map { it.fact.scopeRef.toString() })
        assertEquals(2, search("retry", scope = "area").size, "a bare scope takes every target")
        assertEquals(listOf(retry.id), search("retry", kind = "decision").map { it.fact.id })
        assertEquals(1, search("retry", limit = 1).size)
        assertTrue(search("quantum entanglement").isEmpty())
        assertTrue(search("the of and").isEmpty(), "function words alone search nothing")
    }

    @Test
    fun `a hit is one compact line with the id, kind, scope, text and task`() {
        val fact = put("keep generated clients out of version control", scope = "repo", kind = "decision", task = "TER-7").fact
        assertEquals("${fact.id} decision repo  keep generated clients out of version control (TER-7)", search("generated clients").single().line)
    }

    @Test
    fun `text is one line, bad input is refused with the reason`() {
        assertEquals("a multi line note with a stamp", put("a multi line\n  note <!-- 2026-09-23 13:16:09 orch --> with a stamp").fact.text)
        assertContains(assertFailsWith<IllegalArgumentException> { put("   ") }.message!!, "needs text")
        assertContains(assertFailsWith<IllegalArgumentException> { put("x".repeat(FactText.MAX_LENGTH + 1)) }.message!!, "at most")
        assertContains(assertFailsWith<IllegalArgumentException> { put("a fact", scope = "planet:mars") }.message!!, "scope must start with")
        assertContains(assertFailsWith<IllegalArgumentException> { put("a fact", scope = "file") }.message!!, "needs a target")
        assertContains(assertFailsWith<IllegalArgumentException> { put("a fact", kind = "rumour") }.message!!, "kind must be one of")
    }

    @Test
    fun `an anchor in a checkout nobody can read leaves the fact as it is`() {
        val result = runBlocking { know.put(FactDraft("file:src/Foo.kt", "Foo is the entry point", root = "C:/nowhere")) }
        assertEquals(listOf(Anchor("C:/nowhere", "src/Foo.kt", null)), result.fact.anchors, "a file scope anchors itself")
        assertEquals(FactStatus.ACTIVE, result.fact.status)
        assertNull(result.fact.verifiedAtCommit)
        assertEquals(1, runBlocking { know.stale() }.unreachable)
    }

    @Test
    fun `a folder without an index is still asked whether the cited file exists`() {
        val folder = TestRepos.tmpDir("plain")
        try {
            folder.resolve("notes.md").writeText("notes")
            val anchors = listOf(AnchorRef(root = folder.toString(), path = "notes.md"), AnchorRef(root = folder.toString(), path = "gone.md"))
            val fact = runBlocking { know.put(FactDraft("area:notes", "a note about the notes", anchors = anchors, verify = false)) }.fact
            assertEquals(FactStatus.STALE, fact.status)
            assertEquals("file gone: gone.md", fact.staleReason)
            Files.delete(folder.resolve("notes.md"))
            assertEquals("file gone: notes.md; file gone: gone.md", runBlocking { know.stale() }.stale.single().fact.staleReason)
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an anchor path that leaves its checkout is refused`() {
        for (path in listOf("../secret.txt", "a/../../b.kt", "/etc/passwd", "C:/Windows/win.ini")) {
            assertContains(
                assertFailsWith<IllegalArgumentException> { runBlocking { know.put(FactDraft("repo", "a fact", root = "/work", anchors = listOf(AnchorRef(path = path)))) } }.message!!,
                "relative to its checkout",
            )
        }
    }

    @Test
    fun `an anchor without any checkout is refused`() {
        assertContains(
            assertFailsWith<IllegalArgumentException> { runBlocking { know.put(FactDraft("repo", "a fact", anchors = listOf(AnchorRef(path = "a/b.kt")))) } }.message!!,
            "needs a checkout",
        )
    }

    @Test
    fun `facts and ids survive a restart`() {
        val id = put("durable fact about restarts").fact.id
        know.close()
        know = open()
        assertEquals(id, search("restarts").single().fact.id)
        assertEquals(id, put("durable fact about restarts").fact.id, "dedupe sees facts written before the restart")
        assertFalse(put("another fact").fact.id == id)
    }
}
