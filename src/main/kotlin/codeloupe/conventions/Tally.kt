package codeloupe.conventions

/** Counts per key with the alphabetically first example of each, so the same code always gives the same card. */
class Tally {
    class Entry(val key: String, val count: Int, val example: String)

    private val counts = HashMap<String, Int>()
    private val examples = HashMap<String, String>()

    fun add(key: String, example: String = key) {
        counts.merge(key, 1, Int::plus)
        examples.merge(key, example) { a, b -> if (b < a) b else a }
    }

    val total: Int get() = counts.values.sum()

    /** The [k] most frequent keys that occur at least [min] times, most frequent first, ties by key. */
    fun top(k: Int, min: Int = 1): List<Entry> =
        counts.entries.filter { it.value >= min }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(k).map { Entry(it.key, it.value, examples.getValue(it.key)) }
}
