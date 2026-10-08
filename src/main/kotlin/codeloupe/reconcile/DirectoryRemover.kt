package codeloupe.reconcile

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a directory tree without following links (a junction or symlink is removed as a link, its target is left
 * alone). Whatever cannot be deleted - a file a process on Windows still holds - ends the walk with an [IOException]
 * naming it; what was deleted stays deleted, so the next attempt has less to do.
 */
object DirectoryRemover {
    fun remove(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    delete(file)
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
