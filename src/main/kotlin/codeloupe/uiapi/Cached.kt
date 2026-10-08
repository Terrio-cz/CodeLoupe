package codeloupe.uiapi

/** One value kept for [ttlMs]; a refresh by one caller serves the others that arrive meanwhile. */
internal class Cached<T : Any>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private var value: T? = null
    private var at = 0L

    @Synchronized
    fun peek(): T? = value?.takeIf { clock() - at < ttlMs }

    /** The last value whatever its age, for a caller that refreshes in the background. */
    @Synchronized
    fun latest(): T? = value

    @Synchronized
    fun put(next: T): T {
        value = next
        at = clock()
        return next
    }

    suspend fun get(load: suspend () -> T): T = peek() ?: put(load())
}
