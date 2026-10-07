package codeloupe.index

import codeloupe.TestRepos
import codeloupe.query.FindQuery
import codeloupe.query.View
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BaseBuilderTest {
    /** A recursion-deep expression (as in generated code) must neither overflow nor fail the other files. */
    @Test
    fun `a very deep file is indexed and does not fail the build`() {
        val deep = "package deep\n\nval total = " + (1..20_000).joinToString(" + ") { "\"t$it\"" } + "\n\nfun after() = 1\n"
        val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf("src/main/kotlin/deep/Deep.kt" to deep))
        val db = TestRepos.tmpDir("deep").resolve("base.db")
        val result = BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), db)
        assertEquals(2, result.files)
        View(db).use { view ->
            assertContains(FindQuery.run(view, FindQuery.Args("OrderService", kind = "class")), "class OrderService")
            assertContains(FindQuery.run(view, FindQuery.Args("after")), "Deep.kt")
        }
    }
}
