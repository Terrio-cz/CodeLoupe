package codeloupe.write

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** One lock per file path, taken in path order so two writers of several files cannot wait for each other. */
internal class FileLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withAll(paths: Collection<String>, block: () -> T): T {
        val held = paths.map { it.lowercase() }.distinct().sorted().map { locks.computeIfAbsent(it) { ReentrantLock() } }
        held.forEach { it.lock() }
        try {
            return block()
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }
}
