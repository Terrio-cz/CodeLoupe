package codeloupe.query.usages

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.query.View
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** Subtypes that share a directory name it once instead of on every line. */
class HierarchyCompactTest {
    @Test
    fun `subtypes beside the type are written relative, the others share a directory named in the heading`() {
        val dir = "src/main/kotlin/demo/families"
        val impl = "src/main/kotlin/demo/impl"
        val repo = TestRepos.fixtureRepo(
            "kotlin/sample",
            mapOf(
                "$dir/Family.kt" to "package demo.families\n\ninterface Family\n",
                "$dir/Beside.kt" to "package demo.families\n\nobject Beside : Family\n",
                "$impl/AFamily.kt" to "package demo.impl\n\nimport demo.families.Family\n\nobject AFamily : Family\n",
                "$impl/BFamily.kt" to "package demo.impl\n\nimport demo.families.Family\n\nobject BFamily : Family\n",
            ),
        )
        val db = TestRepos.tmpDir("hierarchy-compact").resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), db)
        View(db).use { view ->
            val text = HierarchyQuery.run(view, "Family", supers = true)
            assertContains(text, "$dir/Family.kt:3  interface Family")
            assertContains(text, "subtypes (under $impl/):")
            assertContains(text, "  ./Beside.kt:3  object Beside : Family")
            assertContains(text, "  AFamily.kt:5  object AFamily : Family")
            assertFalse("$impl/AFamily.kt" in text, text)
            val plain = HierarchyQuery.run(view, "Family")
            assertFalse("interface Family" in plain, "no head line by default: $plain")
            assertContains(plain, "subtypes (under src/main/kotlin/demo/):")
        }
    }

    @Test
    fun `supertypes are the direct ones unless deep`() {
        val dir = "src/main/kotlin/demo/chain"
        val repo = TestRepos.fixtureRepo(
            "kotlin/sample",
            mapOf(
                "$dir/Top.kt" to "package demo.chain\n\ninterface Top\n",
                "$dir/Middle.kt" to "package demo.chain\n\ninterface Middle : Top\n",
                "$dir/Leaf.kt" to "package demo.chain\n\nclass Leaf : Middle\n",
            ),
        )
        val db = TestRepos.tmpDir("hierarchy-deep").resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), db)
        View(db).use { view ->
            assertFalse("supertypes" in HierarchyQuery.run(view, "Leaf"), "supertypes only on request")
            val direct = HierarchyQuery.run(view, "Leaf", supers = true)
            assertContains(direct, "interface Middle")
            assertFalse("interface Top" in direct, direct)
            assertContains(HierarchyQuery.run(view, "Leaf", deep = true), "interface Top")
            assertFalse("class Leaf" in HierarchyQuery.run(view, "Top"), "direct subtypes only")
            assertContains(HierarchyQuery.run(view, "Top", deep = true), "class Leaf")
        }
    }
}
