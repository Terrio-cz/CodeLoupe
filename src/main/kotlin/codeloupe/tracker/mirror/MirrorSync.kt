package codeloupe.tracker.mirror

import codeloupe.tracker.TrackerAdapter
import codeloupe.tracker.TrackerException

/**
 * Brings one project of the mirror up to date: a full load the first time, afterwards only the issues whose
 * `updated` moved, asked for from the project's watermark (the newest `updated` the tracker itself listed) minus
 * [MARGIN_MS]. Every [CHECK_MS] a full id listing removes deleted or moved issues. Blocking; callers serialize per project.
 */
class MirrorSync(
    private val adapter: TrackerAdapter,
    private val store: MirrorStore,
    /** Whether a project belongs to this mirror (a moved issue may land outside it). */
    private val mirrored: (String) -> Boolean,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns how many issues changed. */
    fun sync(project: String): Int {
        val started = clock()
        val state = store.state(project)
        val first = state.syncedAt == null
        val (changed, watermark) = if (first) load(project, started) else update(project, state.watermark, started)
        // A read may have stored a change already; the tracker listing something newer still means new history.
        val moved = (watermark ?: 0) > (state.watermark ?: 0)
        val check = !first && started - (state.checkedAt ?: 0) >= CHECK_MS
        val pruned = if (check) prune(project, started) else 0
        store.markSynced(project, started, watermark, checked = first || check)
        // Field changes always move `updated`: no change, no history request.
        if (first || moved || changed + pruned > 0) history(project)
        return changed + pruned
    }

    /**
     * Re-reads [id] (canonical) when the tracker has a newer version. Returns the id it has now — another one when
     * the issue moved — or null when it no longer exists.
     */
    fun refresh(id: String): String? {
        val updated = adapter.updated(id) ?: return null.also { store.delete(listOf(id)) }
        if (store.issue(id)?.updated == updated) return id
        val issue = adapter.issue(id) ?: return null.also { store.delete(listOf(id)) }
        if (issue.id != id) store.delete(listOf(id))
        if (mirrored(issue.project)) store.upsert(listOf(issue), clock())
        return issue.id
    }

    private fun load(project: String, now: Long): Pair<Int, Long?> {
        val watermark = adapter.newest(project)
        var skip = 0
        var changed = 0
        while (true) {
            val page = adapter.page(project, skip, PAGE)
            changed += store.upsert(page, now)
            if (page.size < PAGE) return changed to watermark
            skip += page.size
        }
    }

    private fun update(project: String, watermark: Long?, now: Long): Pair<Int, Long?> {
        val since = watermark?.minus(MARGIN_MS)
        val known = store.updated(project)
        val stamps = adapter.updatedSince(project, since)
        val stale = stamps.filter { known[it.id] != it.updated }
        val fetched = when {
            stale.isEmpty() -> emptyList()
            // After an idle spell many issues moved: one paged query instead of a call each.
            // An issue updated while the pages are read jumps ahead and is missed: those few are asked for one by one.
            stale.size > ONE_BY_ONE && since != null -> adapter.changedSince(project, since).associateBy { it.id }.let { all ->
                stale.map { s -> s.id to (all[s.id] ?: adapter.issue(s.id)?.takeIf { it.id == s.id }) }
            }
            else -> stale.map { it.id to adapter.issue(it.id) }
        }
        store.delete(fetched.filter { it.second == null }.map { it.first })
        val changed = store.upsert(fetched.mapNotNull { it.second }, now)
        return changed to (stamps.maxOfOrNull { it.updated } ?: watermark)
    }

    private fun prune(project: String, now: Long): Int {
        val remote = adapter.updatedSince(project, null).associate { it.id to it.updated }
        val local = store.updated(project)
        // A listing paged during a delete can skip a live issue: each one is confirmed before it goes.
        val gone = (local.keys - remote.keys).filter { id -> adapter.issue(id)?.id != id }
        store.delete(gone)
        val missing = remote.filter { (id, updated) -> local[id] != updated }.keys
        return gone.size + store.upsert(missing.mapNotNull(adapter::issue), now)
    }

    private fun history(project: String) {
        try {
            val since = store.lastChange(project)?.minus(MARGIN_MS) ?: 0
            store.addChanges(project, adapter.fieldChanges(project, since))
        } catch (e: TrackerException) {
            log("history of $project not updated: ${e.message}")
        }
    }

    companion object {
        const val PAGE = 50
        const val ONE_BY_ONE = 10

        /** Updates can become visible out of order; re-asking for the last minutes catches the late ones. */
        const val MARGIN_MS = 5 * 60_000L
        const val CHECK_MS = 6 * 3600_000L
    }
}
