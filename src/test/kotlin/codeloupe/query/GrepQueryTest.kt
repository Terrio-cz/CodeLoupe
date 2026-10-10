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

class GrepQueryTest {
    private val path = "src/main/kotlin/com/example/shop/Constructs.kt"

    private fun grep(view: View, pattern: String, regex: Boolean = false, ignoreCase: Boolean = false, module: String? = null, limit: Int = 40) =
        GrepQuery.run(view, GrepQuery.Args(pattern, regex = regex, ignoreCase = ignoreCase, module = module, limit = limit))

    @Test
    fun `hits are grouped under the declaration that encloses them`() = View(BASE).use { view ->
        val out = grep(view, "\"audit\"")
        assertContains(out, "grep \"\"audit\"\": 1 hit in 1 file")
        assertContains(out, "$path\n")
        assertContains(out, "\n  [OrderService.Audit] fun record()\n    90 fun record() = log(\"audit\")")
        assertEquals(1, out.lines().count { it.startsWith("    ") })
    }

    @Test
    fun `a hit outside every declaration is file level`() = View(BASE).use { view ->
        assertContains(grep(view, "JvmName"), "(file level)")
    }

    @Test
    fun `several hits of one declaration share one label`() = View(BASE).use { view ->
        val out = grep(view, "svc")
        val labels = out.lines().filter { it.startsWith("  ") && !it.startsWith("    ") }
        assertEquals(labels.distinct(), labels, "a declaration is named once per run of its hits")
        assertContains(out, "fun main()")
    }

    @Test
    fun `literal by default, regex and case on request`() = View(BASE).use { view ->
        assertTrue(grep(view, "handle(").contains("hit"), "a parenthesis is plain text")
        assertContains(grep(view, "handle\\d\\(", regex = true), "handle2")
        assertTrue(grep(view, "SHOUT").startsWith("no match"))
        assertContains(grep(view, "SHOUT", ignoreCase = true), "fun String.shout")
        assertEquals("invalid regex: Unclosed group", grep(view, "(", regex = true).substringBefore(" near"))
    }

    @Test
    fun `module and limit narrow the answer`() = View(BASE).use { view ->
        assertFalse("Constructs.kt" in grep(view, "fun ", module = "big"), "only the big module")
        val capped = grep(view, "fun ", limit = 3)
        assertEquals(3, capped.lines().count { it.startsWith("    ") })
        assertContains(capped, "more hits")
    }

    @Test
    fun `module also takes a directory or a file name prefix of the paths`() = View(BASE).use { view ->
        assertContains(grep(view, "\"audit\"", module = "src/main/kotlin/com/example/shop"), "grep \"\"audit\"\": 1 hit in 1 file")
        assertContains(grep(view, "\"audit\"", module = "src/main/kotlin/com/example/shop/"), "1 hit in 1 file")
        assertContains(grep(view, "\"audit\"", module = "src/main/kotlin/com/example/shop/Constructs"), "1 hit in 1 file")
        assertTrue(grep(view, "\"audit\"", module = "src/main/kotlin/com/example/sho").startsWith("no indexed file"), "a prefix ends at a path separator")
    }

    @Test
    fun `an empty answer says when a module or test option left nothing to search`() = View(BASE).use { view ->
        val none = grep(view, "\"audit\"", module = "nowhere/src/main")
        assertTrue(none.startsWith("no indexed file in module or directory \"nowhere/src/main\"; modules: "), none)
        val narrowed = GrepQuery.run(view, GrepQuery.Args("no-such-literal", module = "big", test = false))
        assertTrue(Regex("no match for \"no-such-literal\" in the \\d+ indexed files of module or directory \"big\" and main sources \\(test=false\\)").matches(narrowed), narrowed)
        assertEquals("no match for \"no-such-literal\" in the indexed source (Kotlin and Java files)", grep(view, "no-such-literal"))
    }

    @Test
    fun `a worktree edit is searched instead of the base copy`() {
        val dir = TestRepos.tmpDir("grep-overlay")
        val overlay = dir.resolve("overlay.db")
        val changed = "package com.example.shop\n\nclass Registry {\n    fun replaced() = \"edited-literal\"\n}\n"
        Store.open(overlay).use { db ->
            StoreWriter(db).use { writer ->
                writer.put(IndexedFile(path, "kotlin", "x", changed.length.toLong(), content = changed), Languages.extract(path, changed)!!)
            }
        }
        View(BASE, overlay).use { view ->
            val out = grep(view, "edited-literal")
            assertContains(out, "[Registry] fun replaced()")
            assertTrue(grep(view, "\"audit\"").startsWith("no match"), "the base copy of the edited file is masked")
        }
    }

    private companion object {
        val REPO = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val BASE = TestRepos.tmpDir("grep-db").resolve("base.db").also { BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), it) }
    }
}
