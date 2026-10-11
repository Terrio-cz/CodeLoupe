package codeloupe.platform

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/**
 * Files and folders only their owner can use, on systems with POSIX permissions (Windows keeps the per-user ACL of
 * `%LOCALAPPDATA%`). The mode is given at creation, so there is no moment when another local user could read.
 */
object OwnerOnly {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val folderMode = PosixFilePermissions.fromString("rwx------")
    private val fileMode = PosixFilePermissions.fromString("rw-------")

    private fun attributes(mode: Set<java.nio.file.attribute.PosixFilePermission>): Array<FileAttribute<*>> =
        if (posix) arrayOf(PosixFilePermissions.asFileAttribute(mode)) else emptyArray()

    /** [folder] and its missing parents, created for the owner only; an existing [folder] is tightened too when [tighten] is set. */
    fun folder(folder: Path, tighten: Boolean = true) {
        val existed = Files.isDirectory(folder)
        Files.createDirectories(folder, *attributes(folderMode))
        if (posix && existed && tighten) runCatching { Files.setPosixFilePermissions(folder, folderMode) }
    }

    /** The daemon's home. A folder that already holds something of the daemon is tightened; any other existing one is somebody's and stays as it is. */
    fun home(home: Path) {
        val ours = !Files.isDirectory(home) || MARKERS.any { Files.exists(home.resolve(it)) }
        folder(home, tighten = ours)
    }

    /** [text] written to [file] through a temporary file in the same folder, owner-only from the first byte, and moved over. */
    fun write(file: Path, text: String) {
        folder(file.parent, tighten = false)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp", *attributes(fileMode))
        try {
            Files.writeString(temp, text)
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Like [write] for a [file] that does not exist yet: false, and nothing changed, when somebody made it first. */
    fun create(file: Path, text: String): Boolean {
        folder(file.parent, tighten = false)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp", *attributes(fileMode))
        try {
            Files.writeString(temp, text)
            return putInPlace(temp, file)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * A hard link fails, in one step, when the name is taken. `Files.move` without REPLACE_EXISTING looks first and renames after on
     * Linux and macOS, so two processes that create the same file together can both pass the look and the second silently replaces
     * the first (two daemons ended up with two tokens, one of them not in the file). A file system without links keeps the move.
     */
    private fun putInPlace(temp: Path, file: Path): Boolean = try {
        Files.createLink(file, temp)
        true
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        false
    } catch (_: java.io.IOException) {
        moveInPlace(temp, file)
    } catch (_: UnsupportedOperationException) {
        moveInPlace(temp, file)
    }

    private fun moveInPlace(temp: Path, file: Path): Boolean = try {
        Files.move(temp, file)
        true
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        false
    }

    private val MARKERS = listOf("daemon.json", "daemon.token", "config.json", "jobs.db", "events.db", "daemon.log", "secrets")
}
