package codeloupe.hooks

/** Remembers the commands already answered, so the same command run again (the agent insisted) is left alone. */
class RepeatGuard(private val capacity: Int = 2_000, private val ttlMs: Long = 30 * 60_000, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > capacity
    }

    /** True the first time [key] is offered within the time to live, and false when it comes again. */
    @Synchronized
    fun firstTime(key: String): Boolean {
        val at = now()
        val earlier = seen[key]
        seen[key] = at
        return earlier == null || at - earlier > ttlMs
    }
}
