package codeloupe.query

import codeloupe.platform.IdleTimer
import java.nio.file.Path

/**
 * Read views kept open between queries: a query no longer opens SQLite, attaches its overlay and prepares its
 * statements again (on Terrio ~10 ms of a 15 ms query). Views of a file are closed before the file is deleted
 * ([release]); all of them after [IDLE_MS] without a query.
 */
class ViewPool {
    private val idle = ArrayList<View>()
    private var clock = 0L
    private var leased = 0

    /** When each file was released, by [clock]; only views leased before that can still hold it. */
    private val releasedAt = HashMap<Path, Long>()
    private val timer = IdleTimer(IDLE_MS, ::closeAll)

    /** A view over [baseFile] with [overlayFile] attached: an idle one, or a new one. Hand it back with [give]. */
    fun take(baseFile: Path, overlayFile: Path?): Lease {
        val (pooled, takenAt) = synchronized(this) {
            leased++
            val i = idle.indexOfLast { it.baseFile == baseFile && it.overlayFile == overlayFile }
            (if (i >= 0) idle.removeAt(i) else null) to ++clock
        }
        return Lease(pooled ?: View(baseFile, overlayFile), takenAt)
    }

    /** Takes [lease] back after a query; a view whose query failed, or whose file was released meanwhile, is closed. */
    fun give(lease: Lease, healthy: Boolean) {
        val view = lease.view
        val usable = healthy && view.reset()
        val closing = synchronized(this) {
            val released = listOfNotNull(view.baseFile, view.overlayFile).any { (releasedAt[it] ?: 0) > lease.takenAt }
            if (usable && !released) idle += view
            if (--leased == 0) releasedAt.clear()
            val beyond = (idle.size - MAX_IDLE).coerceAtLeast(0)
            (if (usable && !released) emptyList() else listOf(view)) + idle.take(beyond).also { idle.removeAll(it) }
        }
        closing.forEach(View::close)
        timer.touch()
    }

    /** Closes the idle views that read [file] and keeps those in use from coming back: [file] is about to be deleted. */
    fun release(file: Path) {
        val closing = synchronized(this) {
            if (leased > 0) releasedAt[file] = ++clock
            idle.filter { it.reads(file) }.also { idle.removeAll(it) }
        }
        closing.forEach(View::close)
    }

    fun closeAll() {
        val closing = synchronized(this) { ArrayList(idle).also { idle.clear() } }
        closing.forEach(View::close)
    }

    /** A view taken from the pool at [takenAt] (the pool's clock). */
    class Lease(val view: View, val takenAt: Long)

    private companion object {
        /** Enough for a few worktrees queried in turn and parallel queries of one. */
        const val MAX_IDLE = 4
        const val IDLE_MS = 60_000L
    }
}
