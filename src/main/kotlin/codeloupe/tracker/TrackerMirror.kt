package codeloupe.tracker

import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.MirrorSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The mirror of one tracker instance: its store and the syncs of its projects, one at a time per project. */
class TrackerMirror(
    val instance: TrackerInstance,
    private val adapter: TrackerAdapter,
    val store: MirrorStore,
    private val freshMs: Long,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = instance.projects.associateWith { Mutex() }
    private val sync = MirrorSync(adapter, store, { it in locks }, { log("tracker ${instance.name} $it") }, clock)

    /** The canonical id of [id] when its project is mirrored here, else null. */
    fun canonical(id: String): String? = adapter.canonical(id)?.takeIf { it.second in locks }?.first

    /** Syncs [project] unless its last sync is younger than [maxAgeMs]; a sync already running is waited for, not repeated. */
    suspend fun syncProject(project: String, maxAgeMs: Long) {
        val lock = locks[project] ?: return
        if (young(project, maxAgeMs)) return
        lock.withLock {
            if (young(project, maxAgeMs)) return
            val started = clock()
            try {
                val changed = withContext(Dispatchers.IO) { sync.sync(project) }
                if (changed > 0) log("tracker ${instance.name} $project synced: $changed changed in ${clock() - started} ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = safe(e)
                store.markFailed(project, reason)
                log("tracker ${instance.name} $project sync failed: $reason")
            }
        }
    }

    suspend fun syncAll(maxAgeMs: Long) = instance.projects.forEach { syncProject(it, maxAgeMs) }

    /**
     * Makes sure the mirrored issue [id] (canonical) is current: skipped right after a project sync, otherwise one cheap
     * `updated` call. Returns null when fine, else a note: gone, moved, or the tracker is unreachable.
     */
    suspend fun refresh(id: String): String? {
        val project = adapter.canonical(id)?.second ?: return null
        val known = store.issue(id) != null
        if (known && young(project, freshMs)) return null
        return try {
            when (val now = withContext(Dispatchers.IO) { sync.refresh(id) }) {
                id -> null
                null -> "no issue $id in ${instance.name}"
                else -> "$id moved to $now"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = safe(e)
            log("tracker ${instance.name} refresh $id failed: $reason")
            if (known) "(offline, mirror of ${Times.short(store.state(project).syncedAt)}: $reason)" else "error: $reason"
        }
    }

    private fun young(project: String, maxAgeMs: Long): Boolean = store.state(project).syncedAt?.let { clock() - it < maxAgeMs } ?: false

    /** Tracker errors are written to be shown; anything else only by its kind, since its message may quote a response. */
    private fun safe(e: Exception): String = (e as? TrackerException)?.message ?: "${e::class.simpleName} while reading ${instance.name}"
}
