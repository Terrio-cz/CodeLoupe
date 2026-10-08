package codeloupe.uiapi

import codeloupe.git.GitObjects
import codeloupe.repo.BusyException
import codeloupe.repo.Registry
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The Branches screen: worktrees of every repository with how far they are from the default branch, and one in detail. */
internal class WorktreeViews(private val registry: Registry, private val catalog: RepoCatalog, private val calls: CallLog, private val scope: CoroutineScope) {
    private val bases = Cached<List<Base>>(BASE_TTL_MS)
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean()
    private val reports = ConcurrentHashMap<String, Remembered>()
    private val computing = ConcurrentHashMap.newKeySet<String>()
    private val one = Mutex()

    /** What costs git work: kept a few seconds. The layer and the call counts are read fresh on every request. */
    private class Base(val summary: WorktreeSummary, val state: codeloupe.repo.RepoState)

    private class Remembered(val head: String, val files: Int, val at: Long, val report: ChangeReport)

    suspend fun all(): List<WorktreeSummary> {
        val day = calls.after(Instant.now().minusSeconds(86_400))
        return current().map { b ->
            b.summary.copy(
                layer = LayerState.of(registry.layer(b.summary.path, b.state)),
                queries24h = day.count { it.root != null && WorktreeId.contains(b.summary.path, it.root) },
            )
        }
    }

    /**
     * The git facts of every worktree. The first request waits for them; later ones get the last answer at once while a
     * refresh runs in the background, so a screen polling every few seconds never waits for a scan of dozens of worktrees.
     */
    private suspend fun current(): List<Base> {
        bases.peek()?.let { return it }
        val stale = bases.latest() ?: return bases.put(build())
        if (refreshing.compareAndSet(false, true)) scope.launch { try { bases.put(build()) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e } finally { refreshing.set(false) } }
        return stale
    }

    suspend fun list(repo: String?, layer: String?, q: String?): WorktreeList {
        val wanted = layer?.let { l -> LayerState.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } ?: throw UiApiException.badRequest("unknown layer $l") }
        val needle = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val items = all().filter { w ->
            (repo == null || w.repoId == repo || w.repoName.equals(repo, ignoreCase = true)) &&
                (wanted == null || w.layer == wanted) &&
                (needle == null || listOfNotNull(w.branch, w.path, w.taskId, w.repoName).any { it.lowercase().contains(needle) })
        }
        return WorktreeList(items)
    }

    /** The detail of worktree [id]; its task is added by the caller, which knows the tracker. */
    suspend fun detail(id: String): WorktreeDetail {
        val summary = all().firstOrNull { it.id == id } ?: throw UiApiException.notFound("no worktree $id")
        val report = reportOf(summary.path, summary.head, strict = true)
            ?: throw UiApiException.busy("the changes of ${summary.path} are being computed; retry in a few seconds")
        val layerFiles = report.changedFiles
        return WorktreeDetail.of(
            summary.copy(changedFiles = report.changedFiles), report.baseRef, report.mergeBase, report.changes, report.callers, report.tests, null,
            WorktreeDetail.LayerIndex(layerFiles, null, emptyList()),
        )
    }

    private suspend fun build(): List<Base> {
        val out = ArrayList<Base>()
        var budget = DECL_REPORTS_PER_LIST
        for (repo in catalog.scan().repos) {
            val state = registry.repo(repo.commonDir)
            val tip = GitObjects.resolve(repo.commonDir, repo.defaultRef)
            for (w in repo.workspaces) {
                val head = w.head ?: continue
                if (w.role == "directory" || w.state == WorkspaceState.ORPHAN) continue
                val isMain = w.role == "main"
                val behind = if (isMain || tip == null) 0 else withContext(Dispatchers.IO) { runCatching { GitObjects.ahead(repo.commonDir, tip, head) }.getOrDefault(0) }
                var files = 0
                var decls = 0
                if (!isMain) {
                    // The declaration counts take seconds on a big branch: a list never waits for them, it starts them and shows them on a later poll.
                    val report = remembered(w.path, head) ?: run { if (budget > 0) { budget--; compute(w.path, head) }; null }
                    files = report?.changedFiles ?: filesOf(w.path)
                    decls = report?.changes?.size ?: 0
                }
                out += Base(
                    WorktreeSummary(
                        id = WorktreeId.of(w.path), repoId = state.id, repoName = repo.name, path = w.path, branch = w.branch, head = head, isMain = isMain,
                        taskId = w.taskId, ahead = w.merge?.ahead ?: 0, behind = behind, changedFiles = files, changedDecls = decls,
                        layer = LayerState.NONE, lastActivityAt = w.lastActivity, queries24h = 0,
                    ),
                    state,
                )
            }
        }
        return out
    }

    private suspend fun filesOf(path: String): Int =
        try { registry.changedFiles(path).let { it.files.size + it.otherFiles.size } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e else 0 }

    private fun compute(path: String, head: String) {
        if (!computing.add(path)) return
        scope.launch {
            try {
                one.withLock { reportOf(path, head, strict = false, onlySmall = true) }
            } finally {
                computing.remove(path)
            }
        }
    }

    private fun remembered(path: String, head: String): ChangeReport? =
        reports[path]?.takeIf { it.head == head && System.currentTimeMillis() - it.at < REPORT_TTL_MS }?.report

    /**
     * The change report of [path] at [head], from memory for a minute. A list asks only for small change sets
     * ([onlySmall]); null when it was not computed (too big or busy; with [strict] a worktree that cannot be read is an error).
     */
    private suspend fun reportOf(path: String, head: String, strict: Boolean, onlySmall: Boolean = false): ChangeReport? {
        remembered(path, head)?.let { return it }
        return try {
            val computed = withTimeoutOrNull(REPORT_TIMEOUT_MS) {
                if (onlySmall) {
                    val set = registry.changedFiles(path)
                    if (set.files.size + set.otherFiles.size > SMALL_CHANGE_SET) return@withTimeoutOrNull null
                }
                registry.changes(path) { set, after, before -> WorktreeChanges.report(set, after, before) }
            }
            computed?.also { reports[path] = Remembered(head, it.changedFiles, System.currentTimeMillis(), it) }
        } catch (e: BusyException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!strict) null else throw UiApiException.unavailable("cannot read the changes of $path: ${e.message.orEmpty().lineSequence().first()}")
        }
    }

    private companion object {
        const val BASE_TTL_MS = 5_000L
        const val REPORT_TTL_MS = 60_000L
        const val REPORT_TIMEOUT_MS = 8_000L
        const val SMALL_CHANGE_SET = 60
        const val DECL_REPORTS_PER_LIST = 6
    }
}
