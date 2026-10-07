package codeloupe.overlay

import codeloupe.lang.Languages
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Relative path -> stamp; [prune] holds relative directories to skip (what git ignores as a whole). An unexpected
     * listing error fails the whole walk: a partial walk would read as deleted files.
     */
    fun scan(root: Path, prune: Set<String>): WorktreeFiles = Timings.measure(TimedPart.SCAN) { Walk(prune).run(root) }

    private class Walk(private val prune: Set<String>) {
        val stamps = ConcurrentHashMap<String, Stamp>()
        val ignoreFiles = ConcurrentHashMap<String, Stamp>()
        private val pending = AtomicInteger()
        private val done = CompletableFuture<Unit>()

        fun run(root: Path): WorktreeFiles {
            fork(root, "")
            try {
                done.get()
            } catch (e: ExecutionException) {
                throw IOException("cannot walk $root: ${e.cause?.message}", e.cause)
            }
            return WorktreeFiles(stamps, ignoreFiles)
        }

        private fun fork(dir: Path, rel: String) {
            pending.incrementAndGet()
            pool.execute {
                try {
                    visit(dir, rel)
                    if (pending.decrementAndGet() == 0) done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                }
            }
        }

        private fun visit(dir: Path, rel: String) {
            if (done.isDone) return
            val entries = list(dir)
            // A nested repository or worktree belongs to itself, as for git.
            if (rel.isNotEmpty() && entries.any { it.first.fileName.toString() == ".git" }) return
            for ((entry, attrs) in entries) {
                val name = entry.fileName.toString()
                val path = if (rel.isEmpty()) name else "$rel/$name"
                if (attrs.isDirectory && name != ".git" && path !in prune) {
                    fork(entry, path)
                } else if (attrs.isRegularFile && Languages.languageOf(name) != null) {
                    stamps[path] = stampOf(attrs)
                } else if (attrs.isRegularFile && name == ".gitignore") {
                    ignoreFiles[path] = stampOf(attrs)
                }
            }
        }

        private fun stampOf(attrs: BasicFileAttributes) = Stamp(attrs.lastModifiedTime().to(TimeUnit.MICROSECONDS), attrs.size())

        /** Entries of one directory with the attributes its listing carries; links are not followed. */
        private fun list(dir: Path): List<Pair<Path, BasicFileAttributes>> {
            val entries = ArrayList<Pair<Path, BasicFileAttributes>>()
            try {
                Files.walkFileTree(
                    dir, emptySet(), 1,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            entries += file to attrs
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
}
