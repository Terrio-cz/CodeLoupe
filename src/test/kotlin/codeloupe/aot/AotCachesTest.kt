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
        Files.write(caches.worker, ByteArray(300))
        Files.writeString(caches.ready, caches.readyContent())
    }

    @Test
    fun `the caches are named beside one another after the install and the build`() {
        val caches = caches()
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.cli.aot"), caches.cli)
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.daemon.aot"), caches.daemon)
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.worker.aot"), caches.worker)
        assertEquals(dir.resolve("C__Apps_codeloupe-0123456789ab.ready"), caches.ready)
    }

    @Test
    fun `nothing is ready until all caches and the ready file agree`() {
        val caches = caches()
        val property = "-Dcodeloupe.aot=$prefix"
        assertFalse(caches.isReady())
        assertEquals(listOf(property), caches.daemonFlags(), "a daemon still learns where the caches go")
        assertEquals(emptyList(), caches.workerFlags())
        install(caches)
        assertTrue(caches().isReady())
        assertEquals(listOf(property, "-XX:AOTCache=${caches.daemon}"), caches().daemonFlags())
        assertEquals(listOf("-XX:AOTCache=${caches.worker}"), caches().workerFlags())
    }

    @Test
    fun `a cache that was cut short, removed or made by another runtime is not used`() {
        val caches = caches()
        install(caches)
        assertFalse(caches("jvm-2").isReady(), "made by another runtime")
        assertEquals(emptyList(), caches("jvm-2").workerFlags(), "no worker cache of another runtime")

        // A ready mark of the caches before the parse worker had one (CL-150): trained again, not half used.
        Files.writeString(caches.ready, "jvm-1\n100\n250\n")
        assertFalse(caches().isReady())
        install(caches)
        Files.write(caches.worker, ByteArray(30))
        assertFalse(caches().isReady(), "the worker cache is shorter than when it was marked ready")
        assertEquals(1, caches().daemonFlags().size)
        install(caches)

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

    @Test
    fun `the desktop app's auto names the caches as the launcher scripts do`() {
        assertEquals("C__Users_a_b_AppData_Local_Programs_CodeLoupe_resources_codeloupe".takeLast(60),
            AotInstall.key("C:\\Users\\a b\\AppData\\Local\\Programs\\CodeLoupe\\resources\\codeloupe", windows = true))
        assertEquals("C__Apps_code-loupe_(x86)", AotInstall.key("C:\\Apps\\code-loupe (x86)", windows = true), "the batch file replaces only \\ : space and .")
        assertEquals("_opt_code_loupe_1_0", AotInstall.key("/opt/code-loupe/1.0", windows = false), "the shell script replaces all but letters and digits")

        val lib = Files.createDirectories(dir.resolve("install").resolve("lib"))
        val jar = Files.write(lib.resolve("codeloupe-0.1.0.jar"), "jar".toByteArray())
        val home = dir.resolve("home")
        val prefix = AotInstall.prefix(home, jar.toString())!!
        assertEquals(home.resolve("aot"), prefix.parent)
        // SHA-256 of "jar" starts with these six bytes, as build.gradle.kts names the build.
        assertTrue(prefix.fileName.toString().endsWith("-" + AotInstall.build(jar)), prefix.toString())
        assertEquals(12, AotInstall.build(jar).length)
        assertEquals(null, AotInstall.prefix(home, listOf(jar.toString(), "b.jar").joinToString(java.io.File.pathSeparator)), "not a single jar")
        assertEquals(null, AotInstall.prefix(home, lib.resolve("missing.jar").toString()), "no jar to name the build after")

        val before = System.getProperty(AotCaches.PROPERTY)
        try {
            System.setProperty(AotCaches.PROPERTY, AotInstall.AUTO)
            assertEquals(null, AotCaches.fromProperty(), "auto needs the home")
        } finally {
            if (before == null) System.clearProperty(AotCaches.PROPERTY) else System.setProperty(AotCaches.PROPERTY, before)
        }
    }
}
