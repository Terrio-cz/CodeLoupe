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
    /** Called for every attempt with its trigger: the log, the event stream. */
    private val record: (ActionResult, String) -> Unit,
) {
    private val lock = Mutex()

    /** What would be done now. Reads, removes nothing. */
    suspend fun plan(): ReconcilePlan = lock.withLock { snapshot().second }

    /**
     * Attempts what is allowed: the `auto` entries when [auto], the `confirm` entries named by [confirm] (keys) or
     * [workspaces] (names, any repo). Entries whose backoff has not run out wait, unless [confirm] names them.
     */
    suspend fun run(trigger: String, auto: Boolean, confirm: Set<String> = emptySet(), workspaces: Set<String> = emptySet()): ReconcileRun = lock.withLock {
        val (entries, before) = snapshot()
        val wanted = workspaces.mapTo(HashSet()) { it.lowercase() }
        val results = ArrayList<ActionResult>()
        for (entry in entries.sortedBy { it.kind.ordinal }) {
            val named = entry.key in confirm || entry.workspace?.lowercase() in wanted
            val selected = when (entry.verdict) {
                Verdict.AUTO -> auto || named
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
        val after = if (results.any { it.outcome != ActionOutcome.SKIPPED }) snapshot().second else before
        ReconcileRun(IsoTime.now(), trigger, results, after)
    }

    /** Whether a retry that backed off is due: the scheduler uses it to run between its intervals. */
    fun retryDue(): Boolean = state.anyDue()

    private fun skipped(entry: PlanEntry, detail: String, trigger: String): ActionResult =
        ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.SKIPPED, detail).also { record(it, trigger) }

    // The plan with the backoff state merged in; also forgets the state of what is no longer planned.
    private suspend fun snapshot(): Pair<List<PlanEntry>, ReconcilePlan> {
        val list = registry()
        val report = inventory(list)
        val entries = planner.plan(report.resources, list).map { entry ->
            state.get(entry.key)?.let { entry.copy(attempts = it.attempts, nextAttempt = it.nextAttempt, lastError = it.lastError) } ?: entry
        }
        // Only removable entries keep a backoff. Docker not answering leaves its entries out of the plan, which must not wipe it.
        if (report.engine != null) state.retain(entries.filter { it.verdict == Verdict.AUTO || it.verdict == Verdict.CONFIRM }.mapTo(HashSet()) { it.key })
        val counts = entries.groupingBy { it.verdict.name.lowercase() }.eachCount().toSortedMap()
        return entries to ReconcilePlan(IsoTime.now(), config.auto, counts, entries, report.problems)
    }
}
