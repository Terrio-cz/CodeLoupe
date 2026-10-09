package codeloupe.platform

import codeloupe.TestRepos
import kotlin.test.Test
import kotlin.test.assertEquals

class UserHomeTest {
    private val elsewhere = TestRepos.tmpDir("user-home")

    @Test
    fun `outside Windows the home is the HOME variable, as the hook script, the launcher and git read it`() {
        val home = elsewhere.toString().replace('\\', '/')
        assertEquals(home, UserHome.resolve(mapOf("HOME" to home), "/passwd/home", windows = false).replace('\\', '/'))
    }

    @Test
    fun `without a usable HOME the JVM's own home stays`() {
        assertEquals("/passwd/home", UserHome.resolve(emptyMap(), "/passwd/home", windows = false))
        assertEquals("/passwd/home", UserHome.resolve(mapOf("HOME" to ""), "/passwd/home", windows = false))
        assertEquals("/passwd/home", UserHome.resolve(mapOf("HOME" to "relative/dir"), "/passwd/home", windows = false))
        assertEquals("/passwd/home", UserHome.resolve(mapOf("HOME" to "/no/such/dir/anywhere"), "/passwd/home", windows = false))
    }

    @Test
    fun `on Windows the profile directory stays whatever HOME says`() {
        assertEquals("C:\\Users\\me", UserHome.resolve(mapOf("HOME" to elsewhere.toString()), "C:\\Users\\me", windows = true))
    }
}
