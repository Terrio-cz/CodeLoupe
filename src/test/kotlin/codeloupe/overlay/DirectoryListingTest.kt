package codeloupe.overlay

import codeloupe.TestRepos
import codeloupe.platform.NativeCalls
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.text.Normalizer
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JDK listing on every OS, and the native Windows listing answering exactly what the JDK's does. */
class DirectoryListingTest {
    private val dir = TestRepos.tmpDir("listing")

    private fun shape(entries: List<DirEntry>) = entries.map { listOf(it.name, it.directory, it.regular, it.mtime, it.size) }.sortedBy { it[0] as String }

    private fun populate() {
        dir.resolve("src/ünï côde").createDirectories()
        dir.resolve("empty").createDirectories()
        dir.resolve("A.kt").writeText("class A\n")
        dir.resolve("b b.kt").writeText("x".repeat(70_000))
        dir.resolve(".gitignore").writeText("build/\n")
        dir.resolve("zero.kt").writeText("")
        Files.setLastModifiedTime(dir.resolve("A.kt"), FileTime.fromMillis(1_700_000_000_123))
        Files.setLastModifiedTime(dir.resolve("b b.kt"), FileTime.fromMillis(1_500_000_000_999))
    }

    @Test
    fun `the JDK listing gives names, kinds, times and sizes, without dot entries`() {
        populate()
        val entries = JdkListing.list(dir).associateBy { it.name }
        assertEquals(setOf("src", "empty", "A.kt", "b b.kt", ".gitignore", "zero.kt"), entries.keys)
        assertEquals(70_000L, entries.getValue("b b.kt").size)
        assertEquals(0L, entries.getValue("zero.kt").size)
        assertTrue(entries.getValue("src").directory && !entries.getValue("src").regular)
        assertTrue(entries.getValue("A.kt").regular && !entries.getValue("A.kt").directory)
        assertEquals(1_500_000_000_999_000L, entries.getValue("b b.kt").mtime)
        assertEquals(listOf("ünï côde"), JdkListing.list(dir.resolve("src")).map { it.name })
        assertEquals(emptyList(), JdkListing.list(dir.resolve("empty")))
    }

    @Test
    fun `a directory that is gone lists as empty`() {
        assertEquals(emptyList(), JdkListing.list(dir.resolve("missing")))
    }

    @Test
    fun `a file with a composed non-ASCII name is listed under the name git shows`() {
        val name = Normalizer.normalize("café ñandú.kt", Normalizer.Form.NFC)
        dir.resolve(name).writeText("class C\n")
        TestRepos.git(dir, "init", "-q", "-b", "main")
        TestRepos.git(dir, "add", "-A")
        val tracked = TestRepos.git(dir, "-c", "core.quotepath=off", "ls-files")
        val listed = JdkListing.list(dir).single { it.regular }.name
        assertEquals(Normalizer.normalize(tracked, Normalizer.Form.NFC), Normalizer.normalize(listed, Normalizer.Form.NFC), "the same file")
        assertEquals(tracked, listed, "the same spelling, so the overlay finds the base file under the name the walk gives")
    }

    @Test
    fun `a decomposed name is kept as it is where the file system keeps it`() {
        val nfd = Normalizer.normalize("é.kt", Normalizer.Form.NFD)
        dir.resolve(nfd).writeText("class E\n")
        val listed = JdkListing.list(dir).single().name
        // APFS and NTFS take either spelling for one file; ext4 stores the bytes it was given.
        assumeTrue(listed == nfd, "this file system normalises names, listing returns ${listed.map { it.code }}")
        assertEquals(nfd, listed)
    }

    @Test
    fun `the native listing equals the JDK listing in names, kinds, times and sizes`() {
        assumeTrue(NativeCalls.isWindows, "the native listing is Windows only")
        populate()
        val native = assertNotNull(WindowsListing.list(dir))
        assertEquals(shape(JdkListing.list(dir)), shape(native))
        assertEquals(6, native.size)
        assertEquals(70_000L, native.first { it.name == "b b.kt" }.size)
        assertTrue(native.first { it.name == "src" }.directory)
        assertEquals(emptyList(), WindowsListing.list(dir.resolve("empty")))
    }

    @Test
    fun `a directory that is not there is left to the JDK, a path beyond the classic limit is listed natively`() {
        assumeTrue(NativeCalls.isWindows, "the native listing is Windows only")
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
        val created = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        assumeTrue(created, "no privilege to create symbolic links on this machine")
        val jdk = JdkListing.list(dir).first { it.name == "link" }
        assertTrue(!jdk.directory && !jdk.regular)
        if (NativeCalls.isWindows) {
            val native = assertNotNull(WindowsListing.list(dir)).first { it.name == "link" }
            assertTrue(!native.directory && !native.regular)
        }
    }
}
