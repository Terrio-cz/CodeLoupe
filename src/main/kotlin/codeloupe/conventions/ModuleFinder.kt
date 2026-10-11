package codeloupe.conventions

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** The modules under a repository root: every directory with a `src/<set>/kotlin` or `src/<set>/java` tree. */
object ModuleFinder {
    private val SKIPPED = setOf("build", "node_modules", "out", "target", "dist", "resources")

    fun find(root: Path): List<Module> {
        val found = ArrayList<Module>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString().orEmpty()
                if (dir != root && (name.startsWith(".") || name in SKIPPED)) return FileVisitResult.SKIP_SUBTREE
                if (SourceSets.of(dir).isNotEmpty()) found += Module(nameOf(root, dir), dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found.sortedBy { it.name }
    }

    /** `:public-api`, `:importers:cuzk`; the root project itself is `:`. */
    private fun nameOf(root: Path, dir: Path): String {
        val relative = root.relativize(dir).joinToString(":") { it.toString() }
        return ":$relative"
    }
}
