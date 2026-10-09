package codeloupe.platform

import codeloupe.TestRepos
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnerOnlyTest {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private fun mode(path: java.nio.file.Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a file is written whole over an older one, and no temporary file stays`() {
        val dir = TestRepos.tmpDir("owner-only")
        val file = dir.resolve("a").resolve("token")
        OwnerOnly.write(file, "one")
        OwnerOnly.write(file, "two")
        assertEquals("two", Files.readString(file))
        assertEquals(listOf("token"), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() }, "no temporary file stays")
    }

    @Test
    fun `a file written for the owner only has the owner-only mode from the start`() {
        assumeTrue(posix, "no POSIX permissions here")
        val file = TestRepos.tmpDir("owner-only-mode").resolve("a").resolve("token")
        OwnerOnly.write(file, "one")
        assertEquals("rw-------", mode(file))
        assertEquals("rwx------", mode(file.parent))
    }

    @Test
    fun `a new home is private, a daemon home that exists is tightened, a folder that is somebody else's is left alone`() {
        assumeTrue(posix, "no POSIX permissions here")
        val root = TestRepos.tmpDir("owner-only-home")
        val fresh = root.resolve("fresh")
        OwnerOnly.home(fresh)
        assertEquals("rwx------", mode(fresh))

        val ours = Files.createDirectory(root.resolve("ours"))
        Files.writeString(ours.resolve("daemon.json"), "{}")
        Files.setPosixFilePermissions(ours, PosixFilePermissions.fromString("rwxr-xr-x"))
        OwnerOnly.home(ours)
        assertEquals("rwx------", mode(ours))

        val theirs = Files.createDirectory(root.resolve("theirs"))
        Files.setPosixFilePermissions(theirs, PosixFilePermissions.fromString("rwxr-xr-x"))
        OwnerOnly.home(theirs)
        assertEquals("rwxr-xr-x", mode(theirs))
    }
}
