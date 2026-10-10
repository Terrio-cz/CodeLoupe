package codeloupe.know

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Facts anchored to a fixture repository: committed and uncommitted changes of the anchored declarations flag the fact. */
class KnowStaleTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to billing(), NOTES to "Billing notes\n"))
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

    private fun put(text: String, scope: String, vararg anchors: String) = runBlocking { know.put(FactDraft(scope, text, "pattern", anchors = anchors.map { AnchorRef.parse(it) })) }.fact

    private fun line(id: String) = runBlocking { know.search("billing", limit = 30) }.first { it.fact.id == id }.line

    private fun write(path: String, text: String) = repo.resolve(path).also { it.parent.createDirectories() }.writeText(text)

    private fun commit(message: String) {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", message)
    }

    @Test
    fun `a verified fact is flagged stale when its declaration's body changes, and again clean after verify`() {
        val fact = put("billing total adds up the lines before tax", "symbol:$BILLING#Billing.total")
        assertEquals(git(repo, "rev-parse", "HEAD"), fact.verifiedAtCommit)
        val anchor = fact.anchors.single()
        assertTrue(anchor.fingerprinted)
        assertEquals("Billing.total", anchor.symbol)
        assertFalse("STALE" in line(fact.id))

        // Uncommitted: the overlay of the worktree sees the edit.
        write(BILLING, billing(total = "return a + 1"))
        assertContains(line(fact.id), "STALE: body changed: $BILLING#Billing.total")
        assertEquals(FactStatus.STALE, know.get(fact.id)!!.status, "the flag is stored")

        // Committed: the default branch moves, the index follows.
        commit("change total")
        assertContains(line(fact.id), "STALE: body changed")
        assertEquals(listOf(fact.id), runBlocking { know.stale() }.stale.map { it.fact.id })

        val verified = runBlocking { know.verify(fact.id) }!!
        assertTrue(verified.verified)
        assertEquals(git(repo, "rev-parse", "HEAD"), verified.fact.verifiedAtCommit)
        assertFalse("STALE" in line(fact.id))
        assertTrue(runBlocking { know.stale() }.stale.isEmpty())
    }

    @Test
    fun `a changed signature is told from a changed body, and an untouched neighbour stays clean`() {
        val tax = put("billing tax rounds down", "symbol:Billing.tax")
        val total = put("billing total never returns a negative amount", "symbol:Billing.total")
        write(BILLING, billing(tax = "fun tax(a: Int, rate: Int): Int = a * rate / 100"))
        assertContains(line(tax.id), "STALE: signature changed: Billing.tax")
        assertFalse("STALE" in line(total.id))
    }

    @Test
    fun `a file scope anchors the whole file and notices a removed or added declaration, a gone file is stale`() {
        val fact = put("billing lives in one class", "file:$BILLING")
        val doc = put("billing notes explain the rates", "area:billing", NOTES)
        assertTrue(fact.anchors.single().fingerprinted)
        assertFalse(doc.anchors.single().fingerprinted, "a file the index does not hold is checked for existence only")

        write(NOTES, "Billing notes, edited\n")
        assertFalse("STALE" in line(doc.id), "existence is all that is checked for it")

        write(BILLING, billing(extra = "\n    fun discount(): Int = 5\n"))
        assertContains(line(fact.id), "STALE: signature changed: $BILLING")

        Files.delete(repo.resolve(BILLING))
        Files.delete(repo.resolve(NOTES))
        assertContains(line(fact.id), "STALE: file gone: $BILLING")
        assertContains(line(doc.id), "STALE: file gone: $NOTES")
    }

    @Test
    fun `a removed declaration is stale, and a fact whose code returns to the verified state is clean again`() {
        val fact = put("billing legacy stays for old callers", "symbol:Billing.legacy")
        val original = billing()
        write(BILLING, billing(legacy = false))
        assertContains(line(fact.id), "STALE: declaration gone: Billing.legacy")
        write(BILLING, original)
        assertFalse("STALE" in line(fact.id))
        assertNull(know.get(fact.id)!!.staleReason)
    }

    @Test
    fun `a fact written without verifying is only checked for existence until verified`() {
        val fact = runBlocking { know.put(FactDraft("symbol:Billing.total", "imported note about total", verify = false)) }.fact
        assertFalse(fact.anchors.single().fingerprinted)
        assertNull(fact.verifiedAtCommit)
        write(BILLING, billing(total = "return 42"))
        assertEquals(FactStatus.ACTIVE, runBlocking { know.search("imported note total") }.single().fact.status)
        val verified = runBlocking { know.verify(fact.id) }!!
        assertTrue(verified.fact.anchors.single().fingerprinted)
        assertNotNull(verified.fact.verifiedAtCommit)
        write(BILLING, billing(total = "return 43"))
        assertEquals(FactStatus.STALE, runBlocking { know.search("imported note total") }.single().fact.status)
    }

    @Test
    fun `verify refuses a fact whose anchor is gone and says which`() {
        val fact = put("billing legacy stays for old callers", "symbol:Billing.legacy")
        write(BILLING, billing(legacy = false))
        val result = runBlocking { know.verify(fact.id) }!!
        assertFalse(result.verified)
        assertContains(result.message!!, "Billing.legacy")
        assertEquals(FactStatus.STALE, result.fact.status)
        assertNull(runBlocking { know.verify("k4040") })
    }

    @Test
    fun `an anchor of another checkout is read there`() {
        val other = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to billing()))
        try {
            val fact = runBlocking { know.put(FactDraft("area:billing", "billing total in the other repository", anchors = listOf(AnchorRef(root = other.toString(), symbol = "Billing.total")))) }.fact
            assertEquals(other.toString(), fact.anchors.single().root)
            other.resolve(BILLING).writeText(billing(total = "return 7"))
            assertContains(line(fact.id), "STALE: body changed")
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    private fun billing(total: String = "return a", tax: String = "fun tax(a: Int): Int = a / 10", legacy: Boolean = true, extra: String = ""): String =
        "package demo\n\nclass Billing {\n    fun total(a: Int): Int {\n        $total\n    }\n\n    $tax\n" +
            (if (legacy) "\n    fun legacy(): Int = 1\n" else "") + extra + "}\n"

    private companion object {
        const val BILLING = "src/main/kotlin/demo/Billing.kt"
        const val NOTES = "docs/billing-notes.md"
    }
}
