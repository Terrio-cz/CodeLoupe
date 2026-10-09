package codeloupe.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellPathsTest {
    private val paths = ShellPaths("C:/Users/me", windows = true)

    @Test
    fun `a Windows working directory with backslashes is walked up by dot dot like any other`() {
        assertEquals("C:/repo/other/A.kt", paths.resolve("C:\\repo\\sub", "../other/A.kt"))
        assertEquals("C:/repo/sub/src/A.kt", paths.resolve("C:\\repo\\sub", "src/A.kt"))
        assertEquals("C:/repo/sub/src/A.kt", paths.resolve("C:\\repo\\sub\\", "src\\A.kt"))
        assertEquals("C:/repo/A.kt", paths.resolve("C:/repo/sub", "..\\A.kt"))
    }

    @Test
    fun `absolute words and the home directory do not depend on the working directory`() {
        assertEquals("C:/x/A.kt", paths.resolve("C:\\repo", "C:\\x\\A.kt"))
        assertEquals("C:/Users/me/A.kt", paths.resolve("C:\\repo", "~/A.kt"))
        assertEquals("C:/Users/x/A.kt", paths.resolve("C:\\repo", "/c/Users/x/A.kt"))
    }
}
