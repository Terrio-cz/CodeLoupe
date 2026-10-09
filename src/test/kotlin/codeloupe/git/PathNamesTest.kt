package codeloupe.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathNamesTest {
    @Test
    fun `a name with a control character is not plain`() {
        assertTrue(PathNames.plain("src/main/Ünï code.kt"))
        for (bad in listOf("a\nb.kt", "a\u001b[2Jb", "a\u0000b", "a\rb", "tab\u007f")) assertFalse(PathNames.plain(bad), bad)
    }

    @Test
    fun `diff output drops entries with such a path and keeps the rest`() {
        val sha = "1".repeat(40)
        val zero = "0".repeat(40)
        val raw = listOf(":000000 100644 $zero $sha A", "bad\nname.kt", ":000000 100644 $zero $sha A", "good.kt", "").joinToString("\u0000")
        assertEquals(listOf("good.kt"), DiffEntry.parse(raw).map { it.path })
    }
}
