package codeloupe.reconcile

import codeloupe.config.ReconcileConfig
import codeloupe.docker.ResourceReport
import codeloupe.processes.ProcessInventory
import codeloupe.processes.ProcessReport
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings the Docker resources and orphan directories of released workspaces to the state the registry wants: gone.
 * Every run starts from a fresh read of the registry and of Docker (only the dry run [plan] may use the scan the read-only
 * routes share, [planRegistry]), so it is idempotent and needs no memory of earlier
 * runs except the backoff of what could not be removed ([ReconcileState]). Runs never overlap.
 *
 * What may be removed is decided by [ReconcilePlanner] alone: `auto` entries by themselves (when asked to), `confirm`
 * entries only when named by the caller, never `keep` or `protected` ones, and never anything that has no owner.
 */
class Reconciler(
    private val config: ReconcileConfig,
    private val registry: suspend () -> WorkspaceList,
    private val inventory: suspend (Deferred<WorkspaceList>) -> ResourceReport,
    private val planner: ReconcilePlanner,
    private val executor: (PlanEntry) -> ActionResult,
    private val state: ReconcileState,
    /** The released workspaces, when the planner takes them from this store: a mark goes once nothing of it is left. */
    private val releases: ReleaseStore? = null,
    private val log: (String) -> Unit = {},
    /** The build tools working in workspace directories; the reconciler stops the ones the plan allows. */
    private val processes: suspend (Deferred<WorkspaceList>) -> ProcessReport = { ProcessReport(IsoTime.now()) },
    /** The registry for the dry run, which may be a scan shared with other reads; a run never uses it. */
    private val planRegistry: suspend () -> WorkspaceList = registry,
    /** Called when a run changed something, so that scans kept for the read-only routes are dropped. */
    private val changed: () -> Unit = {},
    /** Called for every attempt with its trigger: the log, the event stream. */
    private val record: (ActionResult, String) -> Unit,
) {
    private val lock = Mutex()

    // The entries of the latest snapshot, for the status of releases without asking Docker again.
    @Volatile private var latest: List<PlanEntry>? = null

    /** What would be done now. Reads, removes nothing. */
    suspend fun plan(): ReconcilePlan = lock.withLock { snapshot(planRegistry, overlap = true).plan }

    /**
     * Attempts what is allowed: the `auto` entries when [auto], the `confirm` entries named by [confirm] (keys) or
     * [workspaces] (names, any repo). Entries whose backoff has not run out wait, unless [confirm] names them.
     * With [shownPlan] (the [ReconcilePlan.planHash] the person saw) a confirm is checked against the plan this run executes: a different
     * plan throws [StalePlan] before anything is touched.
     */
    suspend fun run(
        trigger: String,
        auto: Boolean,
        confirm: Set<String> = emptySet(),
        workspaces: Set<String> = emptySet(),
        shownPlan: String? = null,
    ): ReconcileRun = lock.withLock {
        val first = snapshot(registry, overlap = false)
        if (shownPlan != null && shownPlan != first.plan.planHash) throw StalePlan(first.plan)
        // A protect rule that could not be read protects nothing: removing anything on the strength of the others would fail open.
        if (config.invalidProtect > 0) return@withLock ReconcileRun(IsoTime.now(), trigger, emptyList(), first.plan)
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
        val attempted = results.any { it.outcome != ActionOutcome.SKIPPED }
        if (attempted) changed()
        val after = if (attempted) snapshot(registry, overlap = false) else first
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

    // A release is done when Docker and the process table answered and no entry of it is left; a read that failed proves nothing.
    private fun completeReleases(snap: Snapshot) {
        val store = releases ?: return
        if (!snap.complete) return
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
    private class Snapshot(val entries: List<PlanEntry>, val plan: ReconcilePlan, val complete: Boolean)

    private fun protectProblem(): List<String> =
        if (config.invalidProtect > 0) listOf("config workspaces.reconcile.protect has ${config.invalidProtect} rule(s) that cannot be read (an invalid regex?): nothing is removed until it is fixed") else emptyList()

    // [overlap]: Docker and the process table do not depend on the registry scan, so the dry run reads all three at once.
    // A run reads them one after another, in the order its decisions rely on.
    private suspend fun snapshot(read: suspend () -> WorkspaceList, overlap: Boolean): Snapshot = coroutineScope {
        if (!overlap) {
            val list = CompletableDeferred(read())
            val report = inventory(list)
            return@coroutineScope assemble(list.await(), report, processes(list))
        }
        val list = async { read() }
        val report = async { inventory(list) }
        val running = async { processes(list) }
        assemble(list.await(), report.await(), running.await())
    }

    private fun assemble(list: WorkspaceList, report: ResourceReport, running: ProcessReport): Snapshot {
        val entries = planner.plan(report.resources, list, running.processes).map { entry ->
            state.get(entry.key)?.let { entry.copy(attempts = it.attempts, nextAttempt = it.nextAttempt, lastError = it.lastError) } ?: entry
        }
        // A read that failed leaves its entries out of the plan, which must not wipe their backoff or tell a release that nothing is left.
        val complete = report.engine != null && running.problems.none { it.startsWith(ProcessInventory.PROBLEM) }
        if (complete) state.retain(entries.filter { it.verdict == Verdict.AUTO || it.verdict == Verdict.CONFIRM }.mapTo(HashSet()) { it.key })
        val counts = entries.groupingBy { it.verdict.name.lowercase() }.eachCount().toSortedMap()
        if (report.engine != null) latest = entries
        return Snapshot(entries, ReconcilePlan(IsoTime.now(), config.auto, counts, entries, report.problems + running.problems.filter { it !in report.problems } + protectProblem(), PlanHash.of(entries)), complete)
    }
}
