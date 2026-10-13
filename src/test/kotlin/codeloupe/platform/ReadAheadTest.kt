package codeloupe.platform

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadAheadTest {
    @Test
    fun `reads come back in the order of the items, however long each takes`() {
        val items = (0 until 50).toList()
        val seen = ArrayList<Pair<Int, String>>()
        ReadAhead.each(items, { 1_000L }, { Thread.sleep((50 - it) % 7L); "text $it" }) { item, text -> seen += item to text }
        assertEquals(items.map { it to "text $it" }, seen)
    }

    @Test
    fun `no more than the byte limit is read ahead of what was handed over`() {
        val read = AtomicInteger()
        val handed = AtomicInteger()
        var most = 0
        val size = ReadAhead.MAX_BYTES / 4
        ReadAhead.each((0 until 20).toList(), { size }, { read.incrementAndGet() }) { _, _ ->
            most = maxOf(most, read.get() - handed.get())
            handed.incrementAndGet()
        }
        assertEquals(20, handed.get())
        assertTrue(most <= 4, "at most four of a quarter of the limit each: $most")
    }

    @Test
    fun `an error of a read reaches the caller as it was thrown`() {
        assertFailsWith<IOException> {
            ReadAhead.each(listOf(1, 2, 3), { 1L }, { if (it == 2) throw IOException("locked") else it }) { _, _ -> }
        }
    }
}
