package codeloupe.know

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.io.path.deleteExisting
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Facts that name a file or class without a directory are anchored through the index of a fixture repository, and found by that anchor. */
class KnowAnchorSearchTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(
            GATEWAY to "package demo\n\nclass PaymentGateway {\n    fun charge(a: Int): Int = a\n}\n",
            "src/main/kotlin/demo/a/Dup.kt" to "package demo.a\n\nclass DupOne\n",
            "src/main/kotlin/demo/b/Dup.kt" to "package demo.b\n\nclass DupTwo\n",
        ),
    )
    private val root = repo.toString()
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
    private val knowDir = TestRepos.tmpDir("know")
    private val know = Know(knowDir.resolve("know.db"), AnchorChecker(RegistryIndex(registry)), defaultRoot = root)

    @AfterTest
    fun cleanUp() {
        know.close()
        registry.close()
        listOf(knowDir, config.home, repo).forEach { it.toFile().deleteRecursively() }
    }

    private fun put(text: String, scope: String = "area:payments", vararg anchors: String) =
        runBlocking { know.put(FactDraft(scope, text, "pattern", anchors = anchors.map { AnchorRef.parse(it) })) }.fact

    private fun ids(anchor: String, question: String = "", limit: Int = 5) = runBlocking { know.search(question, limit = limit, anchor = anchor) }.map { it.fact.id }

    @Test
    fun `a file and a class named without a directory are anchored where the index finds them, for existence only`() {
        val fact = put("PaymentGateway.kt retries a charge once; the PaymentGateway class never logs card data")
        assertEquals(setOf(Triple(root, GATEWAY, null), Triple(root, null, "PaymentGateway")), fact.anchors.map { it.place }.toSet())
        assertTrue(fact.anchors.none { it.fingerprinted })
        assertEquals(FactStatus.ACTIVE, fact.status)
    }

    @Test
    fun `a name the index does not know, or knows twice, anchors nothing`() {
        assertTrue(put("Missing.kt and the NoSuchThing class and PostgreSQL are unrelated").anchors.isEmpty())
        val fact = put("Dup.kt exists twice", scope = "area:dups")
        assertTrue(fact.anchors.isEmpty(), "two files of that name: no guess")
    }

    @Test
    fun `an explicit anchor of the same place is not doubled`() {
        val fact = put("PaymentGateway.kt is the entry point", "area:payments", GATEWAY)
        assertEquals(1, fact.anchors.size)
        assertTrue(fact.anchors.single().fingerprinted)
    }

    @Test
    fun `a fact is found by the file, the path or the class it is anchored to`() {
        val byFile = put("PaymentGateway.kt retries a charge once and the PaymentGateway class keeps no state")
        val byMember = put("charging rounds the amount down", "symbol:PaymentGateway.charge")
        val other = put("unrelated note about docker", "area:docker")
        assertEquals(setOf(byFile.id), ids("PaymentGateway.kt").toSet())
        assertEquals(setOf(byFile.id), ids(GATEWAY).toSet())
        assertEquals(setOf(byFile.id, byMember.id), ids("PaymentGateway").toSet(), "a class finds the facts of its members as well")
        assertEquals(listOf(byMember.id), ids("PaymentGateway.charge"))
        assertEquals(listOf(byFile.id), ids("PaymentGateway", question = "retries"))
        assertTrue(ids("Dup.kt").isEmpty())
        assertFalse(other.id in ids("PaymentGateway"))
    }

    @Test
    fun `without a question the newest facts come first and the limit applies after the anchor`() {
        val first = put("PaymentGateway.kt retries a charge once")
        put("docker notes", "area:docker")
        val second = put("PaymentGateway.kt never logs card data", "area:logging")
        assertEquals(listOf(second.id, first.id), ids("PaymentGateway.kt"))
        assertEquals(listOf(second.id), ids("PaymentGateway.kt", limit = 1))
    }

    @Test
    fun `a superseded fact leaves the anchor search, a stale one stays and says so`() {
        val old = put("PaymentGateway.kt retries a charge twice")
        runBlocking { know.put(FactDraft("area:payments", "PaymentGateway.kt gives up after one retry and reports the failure", supersedes = listOf(old.id))) }
        assertFalse(old.id in ids("PaymentGateway.kt"))

        repo.resolve(GATEWAY).deleteExisting()
        assertContains(runBlocking { know.search(anchor = "PaymentGateway.kt") }.single().line, "STALE: file gone")
        assertContains(runBlocking { know.stale() }.stale.single().line, "STALE: file gone: $GATEWAY")
    }

    @Test
    fun `a search needs a question or an anchor`() {
        assertTrue(runBlocking { know.search("") }.isEmpty())
        assertFailsWith<IllegalArgumentException> { runBlocking { know.search(anchor = "#") } }
    }

    private companion object {
        const val GATEWAY = "src/main/kotlin/demo/PaymentGateway.kt"
    }
}
