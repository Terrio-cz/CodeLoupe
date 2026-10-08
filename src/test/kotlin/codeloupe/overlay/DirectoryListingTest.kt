package codeloupe.overlay

import codeloupe.TestRepos
import codeloupe.platform.NativeCalls
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The native Windows listing answers exactly what the JDK's does. */
class DirectoryListingTest {
    private val dir = TestRepos.tmpDir("listing")

    private fun shape(entries: List<DirEntry>) = entries.map { listOf(it.name, it.directory, it.regular, it.mtime, it.size) }.sortedBy { it[0] as String }

    @Test
    fun `the native listing equals the JDK listing in names, kinds, times and sizes`() {
        if (!NativeCalls.isWindows) return
        dir.resolve("src/ünï côde").createDirectories()
        dir.resolve("empty").createDirectories()
        dir.resolve("A.kt").writeText("class A\n")
        dir.resolve("b b.kt").writeText("x".repeat(70_000))
        dir.resolve(".gitignore").writeText("build/\n")
        dir.resolve("zero.kt").writeText("")
        Files.setLastModifiedTime(dir.resolve("A.kt"), FileTime.fromMillis(1_700_000_000_123))
        Files.setLastModifiedTime(dir.resolve("b b.kt"), FileTime.fromMillis(1_500_000_000_999))
        val native = assertNotNull(WindowsListing.list(dir))
        assertEquals(shape(JdkListing.list(dir)), shape(native))
        assertEquals(6, native.size)
        assertEquals(70_000L, native.first { it.name == "b b.kt" }.size)
        assertTrue(native.first { it.name == "src" }.directory)
        assertEquals(emptyList(), WindowsListing.list(dir.resolve("empty")))
    }

    @Test
    fun `a directory that is not there is left to the JDK, a path beyond the classic limit is listed natively`() {
        if (!NativeCalls.isWindows) return
        assertNull(WindowsListing.list(dir.resolve("missing")))
        var deep: Path = dir
        repeat(14) { deep = deep.resolve("d".repeat(20)) }
        deep.createDirectories()
        deep.resolve("Deep.kt").writeText("class Deep")
        assertTrue(deep.toString().length > 260)
        assertEquals(shape(JdkListing.list(deep)), shape(assertNotNull(WindowsListing.list(deep))))
        assertEquals(listOf("Deep.kt"), assertNotNull(WindowsListing.list(deep)).map { it.name })
    }

    @Test
    fun `a symbolic link is neither a directory nor a regular file, as for the JDK`() {
        val target = dir.resolve("real").createDirectories()
        val link = dir.resolve("link")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            return // no privilege to create links on this machine
        }
        val jdk = JdkListing.list(dir).first { it.name == "link" }
        assertTrue(!jdk.directory && !jdk.regular)
        if (NativeCalls.isWindows) {
            val native = assertNotNull(WindowsListing.list(dir)).first { it.name == "link" }
            assertTrue(!native.directory && !native.regular)
        }
    }
}
