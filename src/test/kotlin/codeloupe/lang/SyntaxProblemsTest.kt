package codeloupe.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntaxProblemsTest {
    private fun facts(path: String, text: String) = assertNotNull(Languages.extract(path, text))

    @Test
    fun `a Kotlin file that parses has no problems`() {
        val clean = facts("A.kt", "package a\n\nfun f(x: Int): Int = x + 1\n")
        assertEquals(0, clean.errors)
        assertTrue(clean.problems.isEmpty())
    }

    @Test
    fun `a Kotlin syntax error carries its line, column and what the parser expected`() {
        val broken = facts("A.kt", "package a\n\nfun f(x: Int: Int {\n    return 1\n}\n")
        assertTrue(broken.errors > 0)
        assertEquals(broken.errors.coerceAtMost(SyntaxProblem.MAX_KEPT), broken.problems.size)
        val first = broken.problems.first()
        assertEquals(3, first.line)
        assertTrue(first.col in 1..20, "column ${first.col}")
        assertTrue(first.message.isNotBlank())
    }

    @Test
    fun `a Java syntax error carries its place`() {
        val broken = facts("A.java", "package a;\nclass A {\n  int f( { return 1; }\n}\n")
        assertTrue(broken.errors > 0)
        assertEquals(3, broken.problems.first().line)
    }

    @Test
    fun `only the first problems are kept while all are counted`() {
        val many = facts("A.kt", "fun f() {\n" + "    val = \n".repeat(40) + "}\n")
        assertTrue(many.errors > SyntaxProblem.MAX_KEPT)
        assertEquals(SyntaxProblem.MAX_KEPT, many.problems.size)
    }
}
