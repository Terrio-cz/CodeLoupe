package codeloupe.changes

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The limits that keep callers cheap: a name with too many references, and the budget of one call. */
class CallersTest {
    @Test
    fun `a too common name and a spent budget give a note instead of callers`() {
        val repo = TestRepos.fixtureRepo(
            "kotlin/sample",
            mapOf(
                "src/main/kotlin/demo/Lib.kt" to "package demo\n\nfun common() = 1\n\nfun rare() = 2\n",
                "src/main/kotlin/demo/Use.kt" to "package demo\n\nfun use() = common() + common() + common() + rare()\n",
            ),
        )
        val db = TestRepos.tmpDir("callers").resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), db)
        View(db).use { view ->
            fun decl(name: String) = view.decls("d.name = :name", mapOf("name" to name)).single()
            val callers = Callers(UsageFinder(view), maxRefs = 2, budget = 1)
            assertEquals(listOf("callers: over 2 references named common, too common to resolve here (usages lists them)"), callers.of(decl("common")))
            assertEquals(listOf("callers 1: use (Use.kt)"), callers.of(decl("rare")))
            assertEquals(listOf("callers: not resolved, this call's budget of 1 references is spent (usages lists them)"), callers.of(decl("rare")))
        }
    }
}
