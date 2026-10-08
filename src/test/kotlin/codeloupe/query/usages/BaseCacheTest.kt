package codeloupe.query.usages

import codeloupe.TestRepos
import codeloupe.index.BaseBuilder
import codeloupe.index.IndexedFile
import codeloupe.index.Store
import codeloupe.index.StoreWriter
import codeloupe.lang.Languages
import codeloupe.platform.Timings
import codeloupe.query.View
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Derived maps are built once per base generation and shared; an overlay is applied per request and never leaks into the share. */
class BaseCacheTest {
    private fun sqlCount() = Timings.snapshot().getValue("sql").count

    private fun <T> withView(overlay: Path? = null, block: (IndexCache) -> T): T = View(BASE, overlay).use { block(IndexCache(it)) }

    @Test
    fun `supertype and import maps are read once per base generation`() {
        val first = withView { it.supertypedBy("Shape").map { d -> d.name } to it.importsAs("Acc").map { i -> i.fqn } }
        val before = sqlCount()
        val second = withView { it.supertypedBy("Shape").map { d -> d.name } to it.importsAs("Acc").map { i -> i.fqn } }
        assertEquals(0, sqlCount() - before, "the second request reads no row again")
        assertEquals(first, second)
        assertEquals(listOf("Circle"), second.first)
        assertEquals(listOf("com.example.model.Account"), second.second)
    }

    @Test
    fun `name, reference and file lookups of the base are shared too`() {
        withView { it.named("Account"); it.refsNamed("rename"); it.file("src/main/kotlin/com/example/model/Shapes.kt") }
        val before = sqlCount()
        withView { cache ->
            assertTrue(cache.named("Account").isNotEmpty())
            assertTrue(cache.refsNamed("rename").isNotEmpty())
            assertTrue(cache.file("src/main/kotlin/com/example/model/Shapes.kt") != null)
        }
        assertEquals(0, sqlCount() - before)
    }

    @Test
    fun `an overlay shows in its own request only, whatever order requests come in`() {
        val replaced = SHAPES_PATH
        val overlay = overlayWith(
            replaced,
            "package com.example.model\n\nimport java.util.UUID\n\nopen class Shape\n\nclass Square : Shape()\n\nclass Id(val v: UUID)\n",
        )
        fun supertyped(o: Path?) = withView(o) { it.supertypedBy("Shape").map { d -> d.name } }
        fun imported(o: Path?) = withView(o) { it.importsAs("UUID").map { i -> i.fqn } }
        fun named(o: Path?) = withView(o) { it.named("Circle").map { d -> d.src } to it.named("Square").map { d -> d.src } }

        // The overlay request goes first and again last: neither may change what the plain base requests see.
        assertEquals(listOf("Square"), supertyped(overlay), "the overlay's file replaces the base copy")
        assertEquals(listOf("Circle"), supertyped(null))
        assertEquals(listOf("java.util.UUID"), imported(overlay))
        assertEquals(emptyList(), imported(null))
        assertEquals(emptyList<String>() to listOf("ov"), named(overlay))
        assertEquals(listOf("base") to emptyList(), named(null))
        assertEquals(listOf("Square"), supertyped(overlay))
        assertEquals(listOf("Circle"), supertyped(null))

        // Files an overlay holds are read from it, others from the share.
        withView(overlay) { cache ->
            assertEquals(listOf("ov"), cache.file(replaced)!!.let { scope -> cache.named("Square").map { scope.decl(it.id)!!.src } })
            assertTrue(cache.file("src/main/kotlin/com/example/model/Account.kt") != null)
            assertTrue(cache.refsNamed("Shape").any { it.src == "ov" })
            assertTrue(cache.refsNamed("Shape").none { it.path == replaced && it.src == "base" })
        }
        withView { assertTrue(it.refsNamed("Shape").none { r -> r.src == "ov" }); assertTrue(it.named("Id").isEmpty()) }
    }

    @Test
    fun `two overlays over one base do not see each other`() {
        val a = overlayWith(SHAPES_PATH, "package com.example.model\n\nopen class Shape\n\nclass Alpha : Shape()\n")
        val b = overlayWith(SHAPES_PATH, "package com.example.model\n\nopen class Shape\n\nclass Beta : Shape()\n")
        assertEquals(listOf("Alpha"), withView(a) { it.supertypedBy("Shape").map { d -> d.name } })
        assertEquals(listOf("Beta"), withView(b) { it.supertypedBy("Shape").map { d -> d.name } })
        assertEquals(listOf("Alpha"), withView(a) { it.supertypedBy("Shape").map { d -> d.name } })
    }

    @Test
    fun `a deleted file hides its base rows`() {
        val overlay = TestRepos.tmpDir("tomb").resolve("overlay.db")
        Store.open(overlay).use { db -> StoreWriter(db).use { it.tombstone(SHAPES_PATH) } }
        withView(overlay) { cache ->
            assertTrue(cache.supertypedBy("Shape").isEmpty())
            assertTrue(cache.named("Circle").isEmpty())
            assertEquals(null, cache.file(SHAPES_PATH))
        }
        assertEquals(listOf("Circle"), withView { it.supertypedBy("Shape").map { d -> d.name } })
    }

    @Test
    fun `a new base generation starts an empty cache and old ones are bounded`() {
        val one = TestRepos.tmpDir("gen").resolve("base.db").also { Files.copy(BASE, it) }
        val cache = BaseCaches.of(one)
        assertSame(cache, BaseCaches.of(one))
        Files.write(one, Files.readAllBytes(one) + byteArrayOf(0)) // replaced under the same path: another size
        assertNotSame(cache, BaseCaches.of(one))
        val others = (1..3).map { TestRepos.tmpDir("gen").resolve("base.db").also { f -> Files.copy(BASE, f) } }
        val first = BaseCaches.of(others[0])
        others.forEach(BaseCaches::of)
        assertNotSame(first, BaseCaches.of(others[0]), "only the newest generations stay")
        BaseCaches.evict(others[2])
        val last = BaseCaches.of(others[2])
        assertSame(last, BaseCaches.of(others[2]))
    }

    @Test
    fun `an entry heavier than its budget is computed but not kept`() {
        val lru = SharedLru<String, List<Int>>(3) { it.size }
        var computed = 0
        repeat(2) { lru.getOrPut("big") { computed++; listOf(1, 2, 3, 4) } }
        assertEquals(2, computed)
        repeat(2) { lru.getOrPut("a") { computed++; listOf(1, 2) } }
        assertEquals(3, computed)
        lru.getOrPut("b") { computed++; listOf(1, 2) } // evicts "a"
        lru.getOrPut("a") { computed++; listOf(1, 2) }
        assertEquals(5, computed)
    }

    private fun overlayWith(path: String, content: String): Path {
        val overlay = TestRepos.tmpDir("ov").resolve("overlay.db")
        Store.open(overlay).use { db ->
            StoreWriter(db).use { it.put(IndexedFile(path, "kotlin", "x", content.length.toLong(), content = content), Languages.extract(path, content)!!) }
        }
        return overlay
    }

    private companion object {
        const val SHAPES_PATH = "src/main/kotlin/com/example/model/Shapes.kt"
        val REPO = TestRepos.fixtureRepo("kotlin/usages")
        val BASE = TestRepos.tmpDir("basecache").resolve("base.db").also { BaseBuilder.build(REPO.toString(), TestRepos.git(REPO, "rev-parse", "HEAD"), it) }
    }
}
