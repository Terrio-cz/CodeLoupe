package codeloupe.hooks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookTextTest {
    @Test
    fun `a name cannot carry a line break, an escape or a hidden reordering into the context`() {
        assertEquals("feature x[31m y",HookText.line("feature\nx\u001b[31m y"))
        assertEquals("abc", HookText.line("a\u202eb\u2066c\u0000"))
    }

    @Test
    fun `a listing keeps its own lines and indentation and loses the controls`() {
        val text = "src/A.kt\n  fun a()\u001b[0m\r\n\tfun b()"
        assertEquals("src/A.kt\n  fun a()[0m\n\tfun b()", HookText.block(text))
    }

    @Test
    fun `a line longer than any name is cut`() {
        val cut = HookText.line("x".repeat(1000))
        assertEquals(300, cut.length)
        assertTrue(cut.endsWith("…"))
        assertFalse(HookText.block("short").endsWith("…"))
    }
}
