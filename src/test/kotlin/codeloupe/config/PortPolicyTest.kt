package codeloupe.config

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.daemon.Daemon
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortPolicyTest {
    private val cache = TestRepos.tmpDir("cache")
    private val env = mapOf("XDG_CACHE_HOME" to cache.toString())
    private val defaultHome: Path = cache.resolve(CodeLoupe.NAME)

    private fun config(home: Path, port: Int) =
        Config(home, port, queryTimeoutMs = 1_000, buildTimeoutMs = 1_000, buildHeapMb = 512, defaultRoot = null)

    private fun refusal(home: Path, port: Int) = PortPolicy.refusal(config(home, port), env, "Linux")

    @Test
    fun `default home may take the default port, a foreign home may not`() {
        assertNull(refusal(defaultHome, CodeLoupe.DEFAULT_PORT))
        assertNull(refusal(defaultHome.resolve("..").resolve(CodeLoupe.NAME), CodeLoupe.DEFAULT_PORT))
        assertContains(assertNotNull(refusal(TestRepos.tmpDir("foreign"), CodeLoupe.DEFAULT_PORT)), "needs its own port")
    }

    @Test
    fun `a foreign home on a port of its own is fine`() {
        assertNull(refusal(TestRepos.tmpDir("foreign"), 47_392))
    }

    @Test
    fun `the port configured in the default home is reserved too`() {
        Files.createDirectories(defaultHome)
        Files.writeString(defaultHome.resolve("config.json"), """{ "port": 50123 }""")
        assertNull(refusal(defaultHome, 50_123))
        assertNotNull(refusal(TestRepos.tmpDir("foreign"), 50_123))
        assertNotNull(refusal(TestRepos.tmpDir("foreign"), CodeLoupe.DEFAULT_PORT))
        assertNull(refusal(TestRepos.tmpDir("foreign"), 50_124))
    }

    @Test
    fun `a foreign config cannot claim the default port - the daemon refuses before binding`() {
        val home = TestRepos.tmpDir("foreign")
        Files.writeString(home.resolve("config.json"), """{ "port": ${CodeLoupe.DEFAULT_PORT} }""")
        // The real environment decides here: this home is never the user's default home.
        val config = ConfigLoader.load(mapOf("CODELOUPE_HOME" to home.toString()))
        assertContains(assertFailsWith<IllegalStateException> { Daemon.start(config) }.message.orEmpty(), "needs its own port")
    }
}
