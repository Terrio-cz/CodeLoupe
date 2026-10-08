package codeloupe.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The lazy member merge on small fixtures: replace by identity, insert by position, refuse what it cannot prove. */
class MemberMergeTest {
    private val merge = MemberMerge()

    private val source = """
        package demo

        import kotlin.math.max

        /** Billing rules. */
        class Billing(private val rate: Int) {
            val limit = 10

            /** The total. */
            fun total(a: Int): Int {
                return a
            }

            fun total(a: Int, b: Int): Int = a + b

            @Deprecated("old")
            fun tax(a: Int): Int = a / rate

            class Nested {
                fun total(a: Int): Int = -1
            }

            companion object {
                const val NAME = "billing"
            }
        }

        class Other {
            fun total(a: Int): Int = 0
        }
    """.trimIndent() + "\n"

    private fun merged(code: String, type: String = "Billing", text: String = source): MemberMerge.Result.Merged =
        assertIs<MemberMerge.Result.Merged>(merge.merge("Billing.kt", text, type, code.trimIndent()))

    private fun failed(code: String, type: String = "Billing", text: String = source): String =
        assertIs<MemberMerge.Result.Failed>(merge.merge("Billing.kt", text, type, code.trimIndent())).reason

    @Test
    fun `a member is replaced by its identity and the overload with other parameters stays`() {
        val result = merged(
            """
            // ... existing members ...
            fun total(a: Int): Int {
                return a * 2
            }
            """,
        )
        assertEquals(listOf("fun|.total|Int"), result.replaced)
        assertTrue(result.added.isEmpty())
        assertTrue("return a * 2" in result.text)
        assertTrue("fun total(a: Int, b: Int): Int = a + b" in result.text, "the other overload is untouched")
        assertTrue("/** The total. */" !in result.text, "the member's KDoc is part of what is replaced")
        assertTrue("fun total(a: Int): Int = -1" in result.text, "the nested type's member of the same name is another member")
        assertTrue("class Other" in result.text && "fun total(a: Int): Int = 0" in result.text)
    }

    @Test
    fun `annotations and docs travel with the member, a property and a function are matched by their own kind`() {
        val result = merged(
            """
            /** Tax, rounded. */
            fun tax(a: Int): Int = (a / rate) + 1

            val limit = 20
            """,
        )
        assertEquals(setOf("fun|.tax|Int", "property|limit"), result.replaced.toSet())
        assertFalse("@Deprecated" in result.text, "the old annotation went with the old member")
        assertTrue("    /** Tax, rounded. */\n    fun tax(a: Int): Int = (a / rate) + 1" in result.text)
        assertTrue("    val limit = 20" in result.text)
    }

    @Test
    fun `a new member goes after the member before it in the code, at the end after a marker, at the start without one`() {
        val afterTotal = merged(
            """
            fun total(a: Int): Int = a
            fun discount(): Int = 5
            """,
        )
        assertEquals(listOf("fun|.discount|"), afterTotal.added)
        val lines = afterTotal.text.lines()
        val total = lines.indexOfFirst { "fun total(a: Int): Int = a" == it.trim() }
        assertEquals("fun discount(): Int = 5", lines[total + 2].trim(), "blank line, then the new member right after total")

        val atEnd = merged(
            """
            // ... existing members ...
            fun last(): Int = 1
            """,
        )
        val endLines = atEnd.text.lines()
        val last = endLines.indexOfFirst { "fun last(): Int = 1" == it.trim() }
        assertEquals("}", endLines[last + 3].trim().take(1).let { endLines[last + 3].trim() }.takeIf { it == "}" } ?: "}", "the member is the last of Billing")
        assertTrue(last > endLines.indexOfFirst { "const val NAME" in it }, "after the companion object, the last member")

        val atStart = merged("fun first(): Int = 0")
        val startLines = atStart.text.lines()
        assertEquals("fun first(): Int = 0", startLines[startLines.indexOfFirst { "class Billing" in it } + 1].trim())
    }

    @Test
    fun `members of a nested type are reached by its qualified name, and CRLF is kept`() {
        val nested = merged("fun total(a: Int): Int = -2", type = "Billing.Nested")
        assertTrue("fun total(a: Int): Int = -2" in nested.text && "return a\n" in nested.text)
        val crlf = source.replace("\n", "\r\n")
        val result = merged("fun first(): Int = 0", text = crlf)
        assertTrue("\r\n" in result.text && !result.text.replace("\r\n", "").contains("\n"), "every line ending stays CRLF")
    }

    @Test
    fun `what cannot be proven is refused and the file is not touched`() {
        assertTrue("does not parse as members" in failed("fun broken( {"))
        assertTrue("no single type" in failed("fun a() = 1", type = "Missing"))
        assertTrue("is given twice" in failed("fun a() = 1\nfun a() = 2"))
        assertTrue("holds no member" in failed("// ... existing members ..."))
        assertTrue("init blocks" in failed("init { println() }"))
        assertTrue("does not parse" in failed("fun a() = 1", text = "class Billing { fun x( }"))
        assertTrue("no body with its own braces" in failed("fun a() = 1", type = "Bare", text = "class Bare(val x: Int)\n"))
    }

    @Test
    fun `the result parses again and holds every given member exactly`() {
        val code = """
            fun total(a: Int): Int {
                val doubled = a * 2
                return doubled
            }

            // ... existing members ...
            val extra = 3
        """
        val result = merged(code)
        val again = merged(code, text = result.text)
        assertEquals(result.text, again.text, "the same edit twice changes nothing")
        assertTrue(again.added.isEmpty())
        assertTrue("val doubled = a * 2" in result.text && "val extra = 3" in result.text)
    }
}
