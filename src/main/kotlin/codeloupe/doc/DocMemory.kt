package codeloupe.doc

/** What each reader (a session key: the caller's root) was told of each document, least recently used dropped first. */
class DocMemory(private val capacity: Int = 5_000) {
    /**
     * [known] is every section's own hash as of the reader's last digest, outline or delta; [fetched] the hash of each
     * piece of text the reader was shown (`handle` for a section with its sub-sections, `handle~` for its own text).
     */
    class Read(val at: Long, val hash: String, val known: Map<String, String>, val fetched: Map<String, String>)

    private val reads = object : LinkedHashMap<String, Read>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Read>?) = size > capacity
    }

    @Synchronized
    fun get(session: String, id: String): Read? = reads[key(session, id)]

    @Synchronized
    fun put(session: String, id: String, read: Read) {
        reads[key(session, id)] = read
    }

    private fun key(session: String, id: String) = "$session\u0000$id"
}
