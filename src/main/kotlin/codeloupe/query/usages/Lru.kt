package codeloupe.query.usages

/** A map that forgets its least recently used entries beyond [capacity]: caches stay small in a small daemon heap. */
internal class Lru<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
}
