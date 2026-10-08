package codeloupe.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * One registry scan that the read-only routes share for [ttlMs]: callers that arrive while a scan runs wait for it
 * instead of starting their own. [invalidate] drops the value, and a scan that was already running then is not kept.
 * A window of 0 turns the sharing off.
 */
internal class RecentScan(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private class Kept(val list: WorkspaceList, val at: Long)

    private val lock = Mutex()
    private val generation = AtomicLong()
    @Volatile private var kept: Kept? = null

    suspend fun get(load: suspend () -> WorkspaceList): WorkspaceList {
        if (ttlMs <= 0) return load()
        return lock.withLock {
            current() ?: run {
                val started = generation.get()
                val list = load()
                if (started == generation.get()) kept = Kept(list, clock())
                list
            }
        }
    }

    fun invalidate() {
        generation.incrementAndGet()
        kept = null
    }

    private fun current(): WorkspaceList? = kept?.takeIf { clock() - it.at < ttlMs }?.list
}
