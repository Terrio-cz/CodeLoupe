package codeloupe.workspace

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Bytes of the files under a directory, links and junctions not followed. */
internal object DiskSize {
    fun of(dir: Path): Long? {
        if (!Files.isDirectory(dir)) return null
        var total = 0L
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes) =
                    if (attrs.isSymbolicLink || attrs.isOther) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                // A file deleted or locked while a build runs is not worth failing the listing for.
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            },
        )
        return total
    }
}
