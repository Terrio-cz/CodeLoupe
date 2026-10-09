package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** The candidates of a search are chosen among the declarations the filters and the overlay leave, not among all and cut afterwards. */
class SearchFilterPoolTest {
    private val crowd = (1..100).associate { i ->
        "crowd/src/main/kotlin/c/TokenLimit$i.kt" to "package c\n\n/** The token limit, the token limit and the token limit again. */\nclass TokenLimit$i\n"
    }
    private val quota = "quota/src/main/kotlin/q/Quota.kt" to "package q\n\n/** A limit on the budget of tokens. */\nclass Quota\n"
    private val repo = TestRepos.fixtureRepo("kotlin/sample", crowd + quota)
    private val dir = TestRepos.tmpDir("search-pool")
    private val base: Path = dir.resolve("base.db").also { BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), it) }

    private fun search(q: String, module: String? = null, kind: String? = null, overlay: Path? = null): String =
        View(base, overlay).use { FindQuery.run(it, FindQuery.Args(q, kind = kind, module = module, mode = "search", limit = 5)) }

    @Test
    fun `a module filter finds its weak hit although a hundred stronger ones are elsewhere`() {
        assertContains(search("token limit", module = "quota"), "class Quota")
        assertFalse("TokenLimit" in search("token limit", module = "quota"))
    }

    @Test
    fun `files the overlay replaces do not use up the candidates of the base`() {
        val overlay = dir.resolve("overlay.db")
        Store.open(overlay).use { db -> StoreWriter(db).use { writer -> crowd.keys.forEach(writer::tombstone) } }
        assertContains(search("token limit", overlay = overlay), "class Quota")
    }
}
