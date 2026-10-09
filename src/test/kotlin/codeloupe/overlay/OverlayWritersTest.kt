package codeloupe.overlay

import codeloupe.TestRepos
import codeloupe.index.Store
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OverlayWritersTest {
    private val dir = TestRepos.tmpDir("writers")
    private val file = dir.resolve("a.db")

    @Test
    fun `the connection of a refresh is kept for the next one and closed on drop`() {
        OverlayWriters(60_000).use { writers ->
            val first = writers.use(file) { it }
            assertEquals(1, writers.parkedCount())
            val second = writers.use(file) { it }
            assertSame(first, second, "the second refresh reused the open connection")
            assertFalse(second.isClosed)

            writers.drop(file)
            assertEquals(0, writers.parkedCount())
            assertTrue(second.isClosed)
            assertTrue(writers.use(file) { it } !== second, "a new connection after the drop")
        }
    }

    @Test
    fun `a refresh that fails does not leave its connection behind`() {
        OverlayWriters(60_000).use { writers ->
            var used: java.sql.Connection? = null
            assertFailsWith<IllegalStateException> { writers.use(file) { used = it; error("boom") } }
            assertTrue(used!!.isClosed)
            assertEquals(0, writers.parkedCount())
        }
    }

    @Test
    fun `a connection nobody used for the idle time is closed`() {
        OverlayWriters(100).use { writers ->
            val db = writers.use(file) { it }
            val deadline = System.currentTimeMillis() + 5_000
            while (!db.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(db.isClosed)
            assertEquals(0, writers.parkedCount())
        }
    }

    @Test
    fun `what a kept connection wrote is read by another one, and the file can be deleted once it is dropped`() {
        OverlayWriters(60_000).use { writers ->
            writers.use(file) { Store.setMeta(it, "base", "abc") }
            writers.use(file) { Store.setMeta(it, "base", "def") }
            assertEquals("def", Store.open(file, readOnly = true).use { Store.getMeta(it, "base") })
            writers.drop(file)
            java.nio.file.Files.deleteIfExists(file)
            assertFalse(file.exists())
        }
    }
}
