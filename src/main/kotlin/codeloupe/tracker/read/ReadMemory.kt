package codeloupe.tracker.read

/** Who read which issue at which version: per session key (the caller's root), least recently used dropped first. */
class ReadMemory(private val capacity: Int = 10_000) {
    data class Read(val updated: Long, val at: Long, val parts: Parts)

    private val reads = object : LinkedHashMap<String, Read>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Read>?) = size > capacity
    }

    @Synchronized
    fun get(session: String, id: String): Read? = reads[key(session, id)]

    @Synchronized
    fun put(session: String, id: String, read: Read) {
        reads[key(session, id)] = read
    }

    private fun key(session: String, id: String) = "$session\u0000${id.uppercase()}"
}
