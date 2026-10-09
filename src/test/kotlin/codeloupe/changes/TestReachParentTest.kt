package codeloupe.changes

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The row of a removed declaration comes from the merge-base index: its ids mean nothing in the index of the worktree. */
class TestReachParentTest {
    private val path = "core/src/main/kotlin/demo/Billing.kt"
    private val before = "package demo\n\nclass Alpha {\n    fun a1(): Int = 1\n    fun a2(): Int = 2\n}\n\nclass Billing {\n    fun total(): Int = 1\n    fun unused(): Int = 2\n}\n"
    private val after = "package demo\n\nclass Billing {\n    fun total(): Int = 1\n}\n"

    private fun index(source: String) = TestRepos.fixtureRepo("kotlin/sample", mapOf(path to source)).let { repo ->
        TestRepos.tmpDir("reach").resolve("base.db").also { BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), it) }
    }

    @Test
    fun `the parent of a removed member is found by the name of its container, not by the id it had`() {
        val removed = View(index(before)).use { old -> old.baseDecls("d.name = 'unused'").single() }
        View(index(after)).use { now ->
            val reach = TestReach(UsageFinder(now))
            val parent = assertNotNull(reach.parentOf(removed, removed = true))
            assertEquals("Billing", parent.name)
            assertEquals("class", parent.kind)
        }
    }

    @Test
    fun `a removed top-level declaration has no parent, and neither has one whose container is gone`() {
        val removed = View(index(before)).use { old -> old.baseDecls("d.name IN ('Alpha', 'a1')").associateBy { it.name } }
        View(index(after)).use { now ->
            val reach = TestReach(UsageFinder(now))
            assertNull(reach.parentOf(removed.getValue("Alpha"), removed = true))
            assertNull(reach.parentOf(removed.getValue("a1"), removed = true))
        }
    }
}
