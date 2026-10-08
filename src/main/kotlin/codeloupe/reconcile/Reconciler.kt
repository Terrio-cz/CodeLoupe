package codeloupe.reconcile

import codeloupe.config.ReconcileConfig
import codeloupe.docker.ResourceReport
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings the Docker resources and orphan directories of released workspaces to the state the registry wants: gone.
 * Every run starts from a fresh read of the registry and of Docker, so it is idempotent and needs no memory of earlier
 * runs except the backoff of what could not be removed ([ReconcileState]). Runs never overlap.
 *
 * What may be removed is decided by [ReconcilePlanner] alone: `auto` entries by themselves (when asked to), `confirm`
 * entries only when named by the caller, never `keep` or `protected` ones, and never anything that has no owner.
 */
class Reconciler(
    private val config: ReconcileConfig,
    private val registry: suspend () -> WorkspaceList,
    private val inventory: suspend (WorkspaceList) -> ResourceReport,
    private val planner: ReconcilePlanner,
    private val executor: (PlanEntry) -> ActionResult,
    private val state: ReconcileState,
    /** The released workspaces, when the planner takes them from this store: a mark goes once nothing of it is left. */
    private val releases: ReleaseStore? = null,
    private val log: (String) -> Unit = {},
    /** Called for every attempt with its trigger: the log, the event stream. */
    private val record: (ActionResult, String) -> Unit,
) {
    private val lock = Mutex()

    // The entries of the latest snapshot, for the status of releases without asking Docker again.
    @Volatile private var latest: List<PlanEntry>? = null

    /** What would be done now. Reads, removes nothing. */
    suspend fun plan(): ReconcilePlan = lock.withLock { snapshot().plan }

    /**
     * Attempts what is allowed: the `auto` entries when [auto], the `confirm` entries named by [confirm] (keys) or
     * [workspaces] (names, any repo). Entries whose backoff has not run out wait, unless [confirm] names them.
     */
    suspend fun run(trigger: String, auto: Boolean, confirm: Set<String> = emptySet(), workspaces: Set<String> = emptySet()): ReconcileRun = lock.withLock {
        val first = snapshot()
        val entries = first.entries
        val wanted = workspaces.mapTo(HashSet()) { it.lowercase() }
        val results = ArrayList<ActionResult>()
        for (entry in entries.sortedBy { it.kind.ordinal }) {
            val named = entry.key in confirm || entry.workspace?.lowercase() in wanted
            val selected = when (entry.verdict) {
                Verdict.AUTO -> auto || entry.released || named
                // A removal somebody confirmed that has not gone through yet is retried without asking again.
                Verdict.CONFIRM -> named || state.get(entry.key)?.confirmed == true
                Verdict.KEEP, Verdict.PROTECTED -> false
            }
            if (!selected) {
                if (entry.key in confirm) results += skipped(entry, "refused: ${entry.verdict.name.lowercase()} - ${entry.reason}", trigger)
                continue
            }
            if (!state.due(entry.key) && entry.key !in confirm) continue
            val result = executor(entry)
            when (result.outcome) {
                ActionOutcome.REMOVED, ActionOutcome.GONE -> state.succeeded(entry.key)
                else -> state.failed(entry.key, "${result.outcome.name.lowercase()}: ${result.detail}", confirmed = named && entry.verdict == Verdict.CONFIRM)
            }
            record(result, trigger)
            results += result
        }
        val after = if (results.any { it.outcome != ActionOutcome.SKIPPED }) snapshot() else first
        completeReleases(after)
        ReconcileRun(IsoTime.now(), trigger, results, after.plan)
    }

    /** Whether a retry that backed off is due: the scheduler uses it to run between its intervals. */
    fun retryDue(): Boolean = state.anyDue()

    /** Whether any workspace is released and may still have something to clean: that work runs even with `auto` off. */
    fun hasReleases(): Boolean = releases?.isEmpty() == false

    /** The released workspaces with what the latest snapshot still found of them (null before the first one). Instant: no Docker call. */
    fun releaseStatus(): List<ReleaseStatus> = releases?.all().orEmpty().map { r ->
        val left = latest?.filter { it.released && it.repo.equals(r.repo, ignoreCase = true) && it.workspace.equals(r.workspace, ignoreCase = true) }
        ReleaseStatus(r.repo, r.workspace, r.at, left?.size, left?.count { it.attempts > 0 })
    }

    // A release is done when Docker answered and no entry of it is left; a Docker that did not answer proves nothing.
    private fun completeReleases(snap: Snapshot) {
        val store = releases ?: return
        if (!snap.dockerOk) return
        for (r in store.all()) {
            if (snap.entries.none { it.released && it.repo.equals(r.repo, ignoreCase = true) && it.workspace.equals(r.workspace, ignoreCase = true) }) {
                store.complete(r.repo, r.workspace)
                log("reconcile release of ${r.workspace} (${r.repo}) is complete: nothing is left")
            }
        }
    }

    private fun skipped(entry: PlanEntry, detail: String, trigger: String): ActionResult =
        ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.SKIPPED, detail).also { record(it, trigger) }

    // The plan with the backoff state merged in; also forgets the state of what is no longer planned.
    private class Snapshot(val entries: List<PlanEntry>, val plan: ReconcilePlan, val dockerOk: Boolean)

    private suspend fun snapshot(): Snapshot {
        val list = registry()
        val report = inventory(list)
        val entries = planner.plan(report.resources, list).map { entry ->
            state.get(entry.key)?.let { entry.copy(attempts = it.attempts, nextAttempt = it.nextAttempt, lastError = it.lastError) } ?: entry
        }
        // Only removable entries keep a backoff. Docker not answering leaves its entries out of the plan, which must not wipe it.
        if (report.engine != null) state.retain(entries.filter { it.verdict == Verdict.AUTO || it.verdict == Verdict.CONFIRM }.mapTo(HashSet()) { it.key })
        val counts = entries.groupingBy { it.verdict.name.lowercase() }.eachCount().toSortedMap()
        if (report.engine != null) latest = entries
        return Snapshot(entries, ReconcilePlan(IsoTime.now(), config.auto, counts, entries, report.problems), report.engine != null)
    }
}
