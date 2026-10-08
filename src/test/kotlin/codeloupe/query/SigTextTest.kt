package codeloupe.query

import kotlin.test.Test
import kotlin.test.assertEquals

class SigTextTest {
    @Test
    fun `a signature without comments is left as it is`() {
        assertEquals("fun a(x: Int): Int", SigText.plain("fun a(x: Int): Int"))
        assertEquals("val url = \"a/b\"", SigText.plain("val url = \"a/b\""))
    }

    @Test
    fun `KDoc in a parameter list is dropped and the rest goes on one line`() {
        val sig = "class A(\n    /** The id. */\n    val id: Int,\n    /** The name. */\n    val name: String,\n) : B()"
        assertEquals("class A(val id: Int, val name: String,) : B()", SigText.plain(sig))
    }
}
