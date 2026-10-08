package codeloupe.overlay

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** A directory listing through the JDK: attributes come with the listing (`walkFileTree`), links are not followed. */
internal object JdkListing {
    fun list(dir: Path): List<DirEntry> {
        val entries = ArrayList<DirEntry>()
        try {
            Files.walkFileTree(
                dir, emptySet(), 1,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        entries += DirEntry(file.fileName.toString(), attrs.isDirectory, attrs.isRegularFile, attrs.lastModifiedTime().to(TimeUnit.MICROSECONDS), attrs.size())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        if (skippable(exc)) FileVisitResult.CONTINUE else throw exc
                },
            )
        } catch (e: IOException) {
            if (!skippable(e)) throw e
        }
        return entries
    }

    // Deleted while the walk ran (a build cleaning up): gone. Unreadable (a root-owned bind mount, a deny ACL): the
    // same every walk, so skipping it never reads as a deletion; git skips it too.
    private fun skippable(e: IOException) = e is NoSuchFileException || e is AccessDeniedException
}
