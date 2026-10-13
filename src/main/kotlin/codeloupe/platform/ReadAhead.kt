package codeloupe.platform

import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Reads files several at a time and hands them over in their order. On Windows the first open of a file just written waits for the
 * on-access scan (5-15 ms), so the 300 files a coder touched cost seconds one by one while neither disk nor CPU is busy (CL-247).
 * At most [MAX_BYTES] are held at once, and the threads end when idle.
 */
internal object ReadAhead {
    const val MAX_BYTES = 1L * 1024 * 1024
    private const val THREADS = 8

    private val pool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(it, "codeloupe-read").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
    }

    /** Hands [use] each of [items] with what [read] gave for it, in their order; [size] is what reading one holds. */
    fun <T, R> each(items: List<T>, size: (T) -> Long, read: (T) -> R, use: (T, R) -> Unit) {
        if (items.size <= 1) {
            items.forEach { use(it, read(it)) }
            return
        }
        var start = 0
        while (start < items.size) {
            var end = start + 1
            var bytes = size(items[start])
            while (end < items.size && bytes + size(items[end]) <= MAX_BYTES) bytes += size(items[end++])
            val chunk = items.subList(start, end)
            val futures = chunk.map { item -> pool.submit<R> { read(item) } }
            chunk.forEachIndexed { i, item ->
                val value = try {
                    futures[i].get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                use(item, value)
            }
            start = end
        }
    }
}
