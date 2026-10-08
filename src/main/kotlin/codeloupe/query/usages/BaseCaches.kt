package codeloupe.query.usages

import java.nio.file.Files
import java.nio.file.Path

/**
 * The [BaseCache] of each base file queries read. Only the newest generations stay; a file replaced under the same
 * path (a rebuild of one commit) is told by its size and time stamp and starts a new cache.
 */
internal object BaseCaches {
    private const val GENERATIONS = 2

    private class Entry(val stamp: Pair<Long, Long>?, val cache: BaseCache)

    private val generations = LinkedHashMap<Path, Entry>(4, 0.75f, true)

    fun of(baseFile: Path): BaseCache {
        val stamp = stampOf(baseFile) ?: return BaseCache() // gone or unreadable: nothing worth sharing
        synchronized(this) {
            generations[baseFile]?.takeIf { it.stamp == stamp }?.let { return it.cache }
            val entry = Entry(stamp, BaseCache())
            generations[baseFile] = entry
            val oldest = generations.keys.iterator()
            while (generations.size > GENERATIONS && oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
            return entry.cache
        }
    }

    /** Drops what was derived from [baseFile], which is about to be deleted or replaced. */
    fun evict(baseFile: Path) {
        synchronized(this) { generations.remove(baseFile) }
    }

    private fun stampOf(file: Path): Pair<Long, Long>? = runCatching {
        Files.getLastModifiedTime(file).toMillis() to Files.size(file)
    }.getOrNull()
}
