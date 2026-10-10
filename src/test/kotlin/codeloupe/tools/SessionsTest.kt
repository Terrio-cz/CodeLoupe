package codeloupe.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionsTest {
    @Test
    fun `a key is the root with forward slashes and no trailing slash, in lower case`() {
        assertEquals("c:/users/me/repo", Sessions.key(" C:\\Users\\Me\\Repo\\ "))
    }

    @Test
    fun `a caller is near a path when the key is it or lies inside or around it`() {
        val key = Sessions.key("C:/Repos/App")
        assertTrue(Sessions.near(key, "c:\\repos\\app"))
        assertTrue(Sessions.near(key, "C:/Repos/App/src"), "the agent started below the root the caller keyed by")
        assertTrue(Sessions.near(Sessions.key("C:/Repos/App/src"), "C:/Repos/App"), "the agent started above it")
        assertFalse(Sessions.near(key, "C:/Repos/App2"), "a sibling with the same prefix is another worktree")
        assertFalse(Sessions.near(key, ""), "no path forgets nothing")
    }
}
