package codeloupe.reconcile

import codeloupe.config.ReconcileConfig
import codeloupe.events.Event
import codeloupe.events.EventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Runs the plan without anybody asking, when `workspaces.reconcile.auto` is on: once shortly after the daemon starts
 * (so a PC restart is followed by the cleanup it interrupted), after a job finished (a close-out ran), every
 * `intervalMinutes` while a client has talked to the daemon recently, and whenever a backed-off retry falls due.
 * With `auto` off it still finishes the cleanup of released workspaces (`ws release`), at the start, after a job and
 * on retries, never on the interval; the `auto` entries of other workspaces wait for a person then.
 */
class ReconcileScheduler(
    private val scope: CoroutineScope,
    private val config: ReconcileConfig,
    private val reconciler: Reconciler,
    private val events: Flow<Event>,
    /** When a client last called the daemon; null if never. */
    private val lastClientCall: () -> Instant?,
    private val log: (String) -> Unit,
    private val startDelayMs: Long = 20_000,
    private val tickMs: Long = 60_000,
    private val jobDebounceMs: Long = 30_000,
    private val now: () -> Instant = Instant::now,
) {
    private var lastRun: Instant = now()

    // Work to do on its own: everything with `auto`, otherwise only what somebody released.
    private fun wanted(): Boolean = config.auto || reconciler.hasReleases()

    fun start() {
        scope.launch {
            delay(startDelayMs)
            if (wanted()) attempt("start")
        }
        scope.launch {
            while (true) {
                delay(tickMs)
                val interval = Duration.ofMinutes(config.intervalMinutes.toLong())
                val active = lastClientCall()?.let { Duration.between(it, now()) < ACTIVE_WINDOW } == true
                when {
                    wanted() && reconciler.retryDue() -> attempt("retry")
                    config.auto && active && Duration.between(lastRun, now()) >= interval -> attempt("interval")
                }
            }
        }
        scope.launch {
            var pending: Job? = null
            events.filter { it.type == EventTypes.JOB_FINISHED }.collect {
                if (pending?.isActive != true) pending = scope.launch {
                    delay(jobDebounceMs)
                    if (wanted()) attempt("job")
                }
            }
        }
    }

    private suspend fun attempt(trigger: String) {
        lastRun = now()
        try {
            reconciler.run(trigger, auto = config.auto)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("reconcile $trigger failed: ${e::class.simpleName}: ${e.message.orEmpty().lineSequence().first()}")
        }
    }

    private companion object {
        val ACTIVE_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
