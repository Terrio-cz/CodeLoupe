package codeloupe.changes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SigWindowTest {
    @Test
    fun `a short signature stays whole`() {
        assertEquals("fun a(x: Int): Int", SigWindow.clip("fun a(x: Int): Int"))
        assertEquals("fun a(x: Int)" to "fun a()", SigWindow.around("fun a(x: Int)", "fun a()"))
    }

    @Test
    fun `a long signature is cut`() {
        val clipped = SigWindow.clip("class A(" + "private val p: Int, ".repeat(30) + ")")
        assertEquals(160, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `a long changed signature keeps the stretch around the difference`() {
        val before = "class A(" + "private val p: Int, ".repeat(20) + "private val q: Int, ".repeat(20) + ")"
        val after = before.replace("private val q: Int, private val q", "private val q: Int, private val added: Long, private val q")
        val (new, old) = SigWindow.around(after, before)
        assertTrue("added: Long" in new, new)
        assertTrue("added" !in old, old)
        assertTrue(new.length < 230 && old.length < 230)
    }
}
