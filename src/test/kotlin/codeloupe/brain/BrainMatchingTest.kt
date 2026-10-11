package codeloupe.brain

import codeloupe.TestRepos
import codeloupe.index.SearchWords
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The small parts of the ranking: BM25, how a finding names a file, the fingerprint of the brain files and a stale index. */
class BrainMatchingTest {
    @Test
    fun `bm25 ranks the rarer word and the denser entry higher, and a long word matches its longer forms at a lower weight`() {
        val docs = listOf("anti join keyset page", "keyset keyset keyset page", "configuration of the page cache", "something else entirely").map(SearchWords::split)
        val scores = Bm25(docs).scores(SearchWords.terms("anti keyset").keys)
        assertTrue(scores.getValue(0) > scores.getValue(1), "the doc with the rare word wins over the doc with only the common one")
        assertFalse(3 in scores)
        val prefix = Bm25(docs).scores(SearchWords.terms("config").keys)
        assertEquals(setOf(2), prefix.keys, "config finds configuration")
        assertTrue(Bm25(docs).scores(SearchWords.terms("configuration").keys).getValue(2) > prefix.getValue(2))
    }

    @Test
    fun `a file name must equal and the directories a finding gives must appear in order, an elision stands for any`() {
        val full = "billing/src/main/kotlin/com/example/billing/Billing.kt"
        assertTrue(BrainPathKey.of("Billing.kt:88")!!.matches(full))
        assertTrue(BrainPathKey.of("billing/.../Billing.kt:3-9")!!.matches(full))
        assertTrue(BrainPathKey.of("src/main/Billing.kt")!!.matches(full))
        assertFalse(BrainPathKey.of("main/src/Billing.kt")!!.matches(full), "order matters")
        assertFalse(BrainPathKey.of("ui/.../Billing.kt")!!.matches(full))
        assertFalse(BrainPathKey.of("Billing.kts")!!.matches(full))
        assertEquals(null, BrainPathKey.of("..."), "no file name, no key")
        assertEquals(listOf("importers", "importers/ruian"), BrainPathKey.of("importers/ruian/.../X.kt")!!.modulePrefixes())
    }

    @Test
    fun `the fingerprint changes with any brain file and not with the passing of time`() {
        val dir = TestRepos.tmpDir("brain-signature")
        val facts = dir.resolve("facts").also { it.createDirectories() }
        val paths = BrainPaths(facts = facts.toString(), tasks = dir.resolve("tasks").toString(), feedback = dir.resolve("feedback.jsonl").toString(), config = dir.resolve("workspace.json").toString())
        val empty = BrainSignature.of(paths)
        assertEquals(empty, BrainSignature.of(paths))
        facts.resolve("db.md").writeText("+ a\n")
        val one = BrainSignature.of(paths)
        assertNotEquals(empty, one)
        dir.resolve("feedback.jsonl").writeText("{}\n")
        assertEquals(one, BrainSignature.of(paths), "a brief appends to the feedback log: that alone does not rebuild the index")
        dir.resolve("tasks").createDirectories()
        dir.resolve("tasks/TER-1.md").writeText("# TER-1\n")
        assertNotEquals(one, BrainSignature.of(paths))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `an index that cannot be rebuilt stays as it was, and one that was never built says why`() {
        val world = BrainWorld()
        try {
            world.export(listOf(BrainWorld.entry("f-keep00001", "total adds tax")))
            val first = runBlocking { world.service.index(world.root) }
            world.workspace.resolve("brain/facts").also { it.createDirectories() }.resolve("touch.md").writeText("changed")
            world.engine.export = "not json at all"
            assertEquals(1, runBlocking { world.service.index(world.root) }.items.size, "the old index answers while the engine misbehaves")
            assertTrue(first.items.single().entry.id == "f-keep00001")

            val fresh = BrainWorld()
            try {
                fresh.engine.export = "not json at all"
                val e = assertFailsWith<BrainException> { runBlocking { fresh.service.index(fresh.root) } }
                assertTrue("export" in e.message.orEmpty(), e.message)
            } finally {
                fresh.close()
            }
        } finally {
            world.close()
        }
    }
}
