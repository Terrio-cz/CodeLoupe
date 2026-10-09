package codeloupe.workspace

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A few threads for the per-worktree git reads of a registry scan. Each worktree costs ~12 ms of mostly file and pack
 * access, which overlaps well. The threads end after a quiet second, so nothing stays while nobody scans.
 */
internal object ScanPool {
    const val THREADS = 4

    private val pool: ExecutorService by lazy {
        ThreadPoolExecutor(THREADS, THREADS, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(it, "codeloupe-workspace-scan").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
    }

    /** [transform] of every item, in the order of [items]; the first failure is thrown as it was, not wrapped. */
    fun <T, R> map(items: List<T>, parallel: Boolean = true, transform: (Int, T) -> R): List<R> {
        if (!parallel || items.size < 2) return items.mapIndexed(transform)
        val futures = items.mapIndexed { i, item -> pool.submit<R> { transform(i, item) } }
        return futures.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                futures.forEach { f -> f.cancel(false) }
                throw e.cause ?: e
            }
        }
    }
}
