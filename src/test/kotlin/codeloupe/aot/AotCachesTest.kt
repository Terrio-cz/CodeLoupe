package codeloupe.aot

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AotCachesTest {
    private val dir: Path = TestRepos.tmpDir("aot-caches")
    private val prefix = dir.resolve("C__Apps_codeloupe-0123456789ab")

    private fun caches(runtime: String = "jvm-1") = AotCaches(prefix, runtime)

    private fun install(caches: AotCaches) {
        Files.write(caches.cli, ByteArray(100))
        Files.write(caches.daemon, ByteArray(250))
        Files.writeString(caches.ready, caches.readyContent())
    }

    @Test
    fun `the caches are named beside one another after the install and the build`() {
        val caches = caches()
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.cli.aot"), caches.cli)
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.daemon.aot"), caches.daemon)
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.ready"), caches.ready)
    }

    @Test
    fun `nothing is ready until both caches and the ready file agree`() {
        val caches = caches()
        val property = "-Dcodeloupe.aot=$prefix"
        assertFalse(caches.isReady())
        assertEquals(listOf(property), caches.daemonFlags(), "a daemon still learns where the caches go")
        install(caches)
        assertTrue(caches().isReady())
        assertEquals(listOf(property, "-XX:AOTCache=${caches.daemon}"), caches().daemonFlags())
    }

    @Test
    fun `a cache that was cut short, removed or made by another runtime is not used`() {
        val caches = caches()
        install(caches)
        assertFalse(caches("jvm-2").isReady(), "made by another runtime")

        Files.write(caches.cli, ByteArray(40))
        assertFalse(caches().isReady(), "the CLI cache is shorter than when it was marked ready")
        install(caches)
        Files.delete(caches.daemon)
        assertFalse(caches().isReady())
        assertEquals(1, caches().daemonFlags().size, "no AOTCache flag for a missing cache")

        install(caches)
        Files.writeString(caches.ready, "garbage")
        assertFalse(caches().isReady())
    }

    @Test
    fun `only a launcher names the caches`() {
        assertEquals(null, AotCaches.fromProperty())
    }
}
