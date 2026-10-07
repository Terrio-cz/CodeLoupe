package codeloupe.query

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A worktree overlay hides the base copy of every file it holds, deleted files included. */
class ViewOverlayTest {
    @Test
    fun `overlay files mask the base and tombstones hide it`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
        val dir = TestRepos.tmpDir("overlay")
        val base = dir.resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), base)
        val overlay = dir.resolve("overlay.db")
        val changed = "package com.example.shop\n\nclass Registry {\n    fun replaced() = 1\n}\n"
        Store.open(overlay).use { db ->
            StoreWriter(db).use { writer ->
                val path = "src/main/kotlin/com/example/shop/Constructs.kt"
                writer.put(IndexedFile(path, "kotlin", "x", changed.length.toLong(), content = changed), Languages.extract(path, changed)!!)
                writer.tombstone("big/src/main/kotlin/com/example/big/Big.kt")
            }
        }
        View(base, overlay).use { view ->
            assertEquals(listOf("ov"), view.decls("d.name = :name", mapOf("name" to "Registry")).map { it.src })
            assertTrue(view.decls("d.name = :name", mapOf("name" to "OrderService")).isEmpty(), "base copy of a changed file is hidden")
            assertNull(view.file("big/src/main/kotlin/com/example/big/Big.kt"))
            assertEquals(listOf("src/main/kotlin/com/example/shop/Constructs.kt"), view.filesBySuffix("/Constructs.kt"))
            assertTrue(view.filesBySuffix("/Big.kt").isEmpty())
            assertEquals("no declaration matches \"m1\"", FindQuery.run(view, FindQuery.Args("m1")))
            assertTrue(view.refs("r.name = :name", mapOf("name" to "register")).isEmpty(), "base references of a changed file are hidden")
            assertTrue(view.imports("f.path = :path", mapOf("path" to "src/main/kotlin/com/example/shop/Constructs.kt")).isEmpty(), "the overlay copy has no imports")
        }
        View(base).use { assertEquals(listOf("base"), it.decls("d.name = :name AND d.kind = 'class'", mapOf("name" to "OrderService")).map { d -> d.src }) }
    }
}
