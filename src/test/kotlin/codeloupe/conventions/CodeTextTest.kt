package codeloupe.conventions

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeTextTest {
    @Test
    fun `comments and literals become spaces and lines stay where they were`() {
        val source = "val a = \"x // y\" // tail\n/* one /* nested */ still */ val b = 'c'\nval c = \"\"\"raw\n\"quoted\"\n\"\"\"\nthrow Real()\n"
        val blank = CodeText.blank(source)
        assertEquals(source.length, blank.length)
        assertEquals(source.count { it == '\n' }, blank.count { it == '\n' })
        assertEquals(listOf("val a =", "val b =", "val c =", "throw Real()"), blank.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `an unterminated literal ends at the end of its line`() {
        val blank = CodeText.blank("val a = \"open\nthrow X()\n")
        assertEquals("throw X()", blank.lines()[1])
    }
}
