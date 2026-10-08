package codeloupe.query.usages

/**
 * A thread-safe map shared by requests that forgets its least recently used entries once their [weight]s add up past
 * [budget]; an entry heavier than the budget is computed but not kept. Values are computed outside the lock, so two
 * requests may compute the same one — both get an equal result.
 */
internal class SharedLru<K, V : Any>(private val budget: Int, private val weight: (V) -> Int) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0

    fun getOrPut(key: K, compute: () -> V): V {
        synchronized(this) { entries[key] }?.let { return it }
        val value = compute()
        val w = weight(value)
        if (w > budget) return value
        synchronized(this) {
            entries.put(key, value)?.let { total -= weight(it) }
            total += w
            val oldest = entries.entries.iterator()
            while (total > budget && oldest.hasNext()) {
                total -= weight(oldest.next().value)
                oldest.remove()
            }
        }
        return value
    }
}
