package codeloupe.query

import kotlin.test.Test
import kotlin.test.assertEquals

class PathOrderTest {
    /** Expected order taken from `Intl.Collator('und')` (ICU root), which the prototype's localeCompare matched. */
    @Test
    fun `orders paths like Unicode root collation`() {
        val expected = listOf(
            "a b", "a_b", "a-b", "a.b", "a/b", "a1", "a10", "a2", "ab", "aB", "Ab", "AB", "abc",
            "app/src/main/kotlin/Ab.kt", "app/src/main/kotlin/AbC.kt", "app/src/main/kotlin/ab_c.kt", "app-x/a.kt", "app/x.kt",
        )
        val sorted = expected.shuffled(kotlin.random.Random(7)).sortedWith(PathOrder)
        assertEquals(expected.sortedWith(PathOrder), sorted)
        assertEquals(-1, Integer.signum(PathOrder.compare("a-b", "ab")))
        assertEquals(-1, Integer.signum(PathOrder.compare("aB", "Ab")))
        assertEquals(1, Integer.signum(PathOrder.compare("x2", "x10")))
        assertEquals(-1, Integer.signum(PathOrder.compare("app-x/a.kt", "app/x.kt")))
        assertEquals(-1, Integer.signum(PathOrder.compare("a", "a-")))
    }
}
