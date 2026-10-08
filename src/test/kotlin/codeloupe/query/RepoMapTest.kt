package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoMapTest {
    private fun kt(name: String, body: String) = "src/main/kotlin/map/$name.kt" to "package map\n\n$body\n"

    private val repo = TestRepos.fixtureRepo(
        "kotlin/usages",
        mapOf(
            kt("Core", "class Core"),
            kt("UserA", "class UserA { val core = Core() }"),
            kt("UserB", "class UserB { val core = Core() }"),
            kt("UserC", "class UserC { val core = Core(); val b = UserB() }"),
            kt("Lonely", "class Lonely"),
            "src/test/kotlin/map/CoreTest.kt" to "package map\n\nclass CoreTest { val core = Core() }\n",
        ),
    )
    private val base = TestRepos.tmpDir("repomap").resolve("base.db").also { BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), it) }

    private fun map(vararg focus: String, budget: Int = 1500, test: Boolean? = null, view: View = View(base)) =
        view.use { RepoMap.run(it, RepoMap.Args(focus.toList(), budget, test)) }

    private fun files(map: String) = map.lines().filter { it.startsWith("src/") }.map { it.substringAfterLast('/') }

    @Test
    fun `the most referenced file comes first, test sources are left out`() {
        val out = map()
        assertTrue(out.startsWith("map of "), out)
        assertEquals("Core.kt", files(out).first())
        assertFalse("CoreTest" in out, "tests do not vote unless asked")
        assertContains(out, "\n  class Core\n")
        assertTrue(files(out).indexOf("UserB.kt") < files(out).indexOf("UserC.kt"), "a referenced user ranks above the one that only references")
    }

    @Test
    fun `a focus on a file or a symbol ranks its neighbourhood first, references counted both ways`() {
        val byFile = map("map/Lonely.kt")
        assertEquals("Lonely.kt", files(byFile).first())
        assertContains(byFile.lines().first(), "focus src/main/kotlin/map/Lonely.kt")
        val bySymbol = map("UserC")
        assertEquals("UserC.kt", files(bySymbol).first())
        assertEquals(setOf("Core.kt", "UserB.kt"), files(bySymbol).drop(1).take(2).toSet(), "what UserC refers to follows it")
        val users = files(map("Core")).take(5)
        assertTrue("UserA.kt" in users && "UserB.kt" in users && "UserC.kt" in users, "so do the files that refer to the focus: $users")
    }

    @Test
    fun `a focus inside tests brings them in, test=true does too`() {
        assertContains(map("CoreTest"), "class CoreTest")
        assertContains(map(test = true), "class CoreTest")
    }

    @Test
    fun `the budget cuts the map and says what is missing, unknown focus is named`() {
        val small = map(budget = 100)
        assertContains(small, Regex("… \\+\\d+ more files"))
        assertTrue(files(small).isNotEmpty(), "at least the top file")
        assertTrue(map(budget = 100).length < map().length)
        assertContains(map("NoSuchThing", "Core").lines().first(), "not found: NoSuchThing")
    }

    @Test
    fun `an uncommitted edit in a worktree changes the map`() {
        val dir = TestRepos.tmpDir("repomap-overlay")
        val overlay = dir.resolve("overlay.db")
        // Two users now refer to Lonely instead of Core.
        Store.open(overlay).use { db ->
            StoreWriter(db).use { writer ->
                for (name in listOf("UserA", "UserB")) {
                    val path = "src/main/kotlin/map/$name.kt"
                    val changed = "package map\n\nclass $name { val l = Lonely() }\n"
                    writer.put(IndexedFile(path, "kotlin", "x", changed.length.toLong(), content = changed), Languages.extract(path, changed)!!)
                }
            }
        }
        val before = files(map())
        val after = files(map(view = View(base, overlay)))
        assertEquals("Core.kt", before.first())
        assertEquals("Lonely.kt", after.first(), "the edited files' references count, the base copies' do not: $after")
    }

    @Test
    fun `ranks add up and teleport to the focus`() {
        val nodes = setOf("a", "b", "c")
        val edges = mapOf("a" to mapOf("b" to 1.0), "c" to mapOf("b" to 1.0))
        val plain = FileRank.rank(nodes, edges)
        assertEquals(1.0, plain.values.sum(), 1e-6)
        assertTrue(plain.getValue("b") > plain.getValue("a"))
        val focused = FileRank.rank(nodes, edges, setOf("a"))
        assertTrue(focused.getValue("a") > plain.getValue("a"))
        assertEquals(emptyMap(), FileRank.rank(emptySet(), emptyMap()))
    }
}
