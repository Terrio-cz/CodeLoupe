package codeloupe.aot

import codeloupe.cli.CliJvm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** The start scripts and [CliJvm] list the same JVM flags, and the scripts hand the cache location to the CLI. */
class LauncherScriptsTest {
    private val start = Path.of(System.getProperty("codeloupe.projectDir"), "gradle", "start")
    private val scripts = listOf("codeloupe", "codeloupe.bat").associateWith { Files.readString(start.resolve(it)) }

    private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("#") || it.trimStart().startsWith("@rem") }.joinToString(" ")

    @Test
    fun `every flag of a CLI call is in both scripts`() {
        for ((name, text) in scripts) for (flag in CliJvm.args) assertContains(code(text), flag, message = "$name lacks $flag")
    }

    @Test
    fun `both scripts use the AOT cache when it is there and tell the CLI where the caches are`() {
        for ((name, text) in scripts) {
            assertContains(code(text), "-XX:AOTCache=", message = name)
            assertContains(code(text), "-Dcodeloupe.aot=", message = name)
            assertContains(code(text), ".cli.aot", message = name)
        }
    }

    @Test
    fun `the desktop app's auto names the build as the installed scripts do`() {
        val install = Path.of(System.getProperty("codeloupe.projectDir"), "build", "install", "codeloupe")
        val jar = Files.list(install.resolve("lib")).use { files -> files.toList().single { it.fileName.toString().matches(Regex("codeloupe-.*[.]jar")) } }
        val build = AotInstall.build(jar)
        for (name in listOf("codeloupe", "codeloupe.bat")) assertContains(Files.readString(install.resolve("bin").resolve(name)), "-$build", message = name)
    }

    @Test
    fun `no script dumps an archive or gives -Xshare, which the JVM refuses beside an AOT cache`() {
        for ((name, text) in scripts) {
            assertFalse("-Xshare" in code(text), "$name: the JVM refuses -Xshare together with an AOT cache")
            assertFalse("AutoCreateSharedArchive" in code(text) || "ArchiveClassesAtExit" in code(text), "$name: two calls at once must not dump one archive")
        }
    }
}
