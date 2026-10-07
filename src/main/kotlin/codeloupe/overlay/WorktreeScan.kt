package codeloupe.overlay

import codeloupe.lang.Languages
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit

/**
 * Stamps of every indexable file under a worktree, without a git process. Attributes come with the directory
 * listing (`walkFileTree`); asking per file opens it, which on Windows costs ~5x the CPU. Two threads halve the
 * wall time at the same CPU; Terrio (1 100 directories, 2 200 sources): ~30 ms, ~60 ms CPU.
 */
object WorktreeScan {
    private const val THREADS = 2

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(THREADS) { Thread(it, "codeloupe-scan").apply { isDaemon = true } }
    }

    /** Relative path -> stamp; [prune] holds relative directories to skip (what git ignores as a whole). */
    fun scan(root: Path, prune: Set<String>): Map<String, Stamp> {
        val stamps = ConcurrentHashMap<String, Stamp>()
        val pending = Phaser(1)
        fun visit(dir: Path, rel: String) {
            val entries = list(dir) ?: return
            // A nested repository or worktree belongs to itself, as for git.
            if (rel.isNotEmpty() && entries.any { it.first.fileName.toString() == ".git" }) return
            for ((entry, attrs) in entries) {
                val name = entry.fileName.toString()
                val path = if (rel.isEmpty()) name else "$rel/$name"
                if (attrs.isDirectory && name != ".git" && path !in prune) {
                    pending.register()
                    pool.execute {
                        try {
                            visit(entry, path)
                        } finally {
                            pending.arriveAndDeregister()
                        }
                    }
                } else if (attrs.isRegularFile && Languages.languageOf(name) != null) {
                    stamps[path] = Stamp(attrs.lastModifiedTime().to(TimeUnit.MICROSECONDS), attrs.size())
                }
            }
        }
        visit(root, "")
        pending.arriveAndAwaitAdvance()
        return stamps
    }

    /** Entries of one directory with the attributes its listing carries; links are not followed. Null when unreadable. */
    private fun list(dir: Path): List<Pair<Path, BasicFileAttributes>>? {
        val entries = ArrayList<Pair<Path, BasicFileAttributes>>()
        try {
            Files.walkFileTree(
                dir, emptySet(), 1,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        entries += file to attrs
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            return null
        }
        return entries
    }
}
