package codeloupe.overlay

import codeloupe.lang.Languages
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stamps of every indexable file under a worktree, without a git process. Attributes come with the directory
 * listing; asking per file opens it, which on Windows costs ~5x the CPU. Directories are listed on a few threads
 * (a native listing on Windows, [WindowsListing]); Terrio (1 200 directories, 2 200 sources): ~20 ms.
 */
object WorktreeScan {
    // Listing is syscall-bound: more threads than this gave nothing on a 24-core machine, fewer leave cores idle.
    private val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    // Threads end after a quiet half minute: nothing of the pool stays while nobody asks.
    private val pool: ExecutorService by lazy {
        ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(it, "codeloupe-scan").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
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
            val entries = WindowsListing.list(dir) ?: JdkListing.list(dir)
            // A nested repository or worktree belongs to itself, as for git.
            if (rel.isNotEmpty() && entries.any { it.name == ".git" }) return
            for (entry in entries) {
                val name = entry.name
                if (entry.directory) {
                    if (name == ".git") continue
                    val path = join(rel, name)
                    if (path !in prune) fork(dir.resolve(name), path)
                } else if (entry.regular && Languages.languageOf(name) != null) {
                    stamps[join(rel, name)] = Stamp(entry.mtime, entry.size)
                } else if (entry.regular && name == ".gitignore") {
                    ignoreFiles[join(rel, name)] = Stamp(entry.mtime, entry.size)
                }
            }
        }

        private fun join(rel: String, name: String) = if (rel.isEmpty()) name else "$rel/$name"
    }
}
