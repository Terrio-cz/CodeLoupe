package codeloupe.query

import kotlin.test.Test
import kotlin.test.assertEquals

class CommonDirTest {
    @Test
    fun `the directory every path shares is named once`() {
        assertEquals("src/main/kotlin/demo/", CommonDir.of(listOf("src/main/kotlin/demo/A.kt", "src/main/kotlin/demo/B.kt")))
        assertEquals("src/main/kotlin/", CommonDir.of(listOf("src/main/kotlin/demo/A.kt", "src/main/kotlin/other/B.kt")))
    }

    @Test
    fun `a single path, a short prefix or no shared directory is left alone`() {
        assertEquals("", CommonDir.of(listOf("src/main/kotlin/demo/A.kt")))
        assertEquals("", CommonDir.of(listOf("src/main/A.kt", "src/test/B.kt")))
        assertEquals("", CommonDir.of(listOf("A.kt", "B.kt")))
    }

    @Test
    fun `the heading names the directory`() {
        assertEquals("subtypes (under src/a/b/c/d/):", CommonDir.heading("subtypes", "src/a/b/c/d/"))
        assertEquals("subtypes:", CommonDir.heading("subtypes", ""))
    }
}
