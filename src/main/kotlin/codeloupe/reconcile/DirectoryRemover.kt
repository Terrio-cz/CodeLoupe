package codeloupe.reconcile

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a directory tree without following links (a junction or symlink is removed as a link, its target is left
 * alone; a link whose target is gone or that Windows cannot open - a WSL symlink - goes as an entry too). Whatever cannot be deleted - a file a process on Windows still holds - ends the walk with an [IOException]
 * naming it; what was deleted stays deleted, so the next attempt has less to do.
 */
object DirectoryRemover {
    fun remove(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                // The JDK reports a Windows junction as a plain directory (isOther), and walks into it without FOLLOW_LINKS.
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (attrs.isOther && removedAsLink(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    delete(file)
                    return FileVisitResult.CONTINUE
                }

                // A link the walk cannot read (a junction into a deleted directory, a WSL symlink) is only an entry to delete.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (!removedEntry(file)) throw exc
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    // A junction is deleted as an entry whatever its target holds; a real directory with a reparse tag (cloud placeholder) is not empty and is walked.
    private fun removedAsLink(dir: Path): Boolean = try {
        Files.delete(dir)
        true
    } catch (e: DirectoryNotEmptyException) {
        false
    } catch (e: IOException) {
        if (removedEntry(dir)) true else throw e
    }

    // Removes one entry that cannot hold anything of its own: Java's delete, else java.io, else on Windows `rmdir` without /s, which
    // removes a reparse point Java cannot open (ERROR_CANT_ACCESS_FILE) and refuses a directory that still holds files.
    private fun removedEntry(path: Path): Boolean {
        runCatching { Files.deleteIfExists(path) }
        if (!listed(path)) return true
        if (path.toFile().delete() && !listed(path)) return true
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) return false
        val rmdir = ProcessBuilder("cmd", "/c", "rmdir", path.toString()).redirectErrorStream(true).start()
        rmdir.inputStream.readAllBytes()
        rmdir.waitFor()
        return !listed(path)
    }

    // Whether the parent still lists the entry; asking the entry itself fails for a link Windows cannot open.
    private fun listed(path: Path): Boolean {
        val parent = path.parent ?: return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        return Files.list(parent).use { entries -> entries.anyMatch { it.fileName == path.fileName } }
    }

    // Read-only files (git's object files on Windows) refuse deletion until the flag is cleared.
    private fun delete(path: Path) {
        try {
            Files.delete(path)
        } catch (e: AccessDeniedException) {
            if (!path.toFile().setWritable(true)) throw e
            Files.delete(path)
        }
    }
}
