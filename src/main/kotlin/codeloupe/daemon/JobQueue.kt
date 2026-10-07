package codeloupe.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Priority job queue of the daemon. Jobs with the same key coalesce (callers share one result). Lane FAST
 * (overlay refresh, writes) and lane HEAVY (base builds, normally in a child process) each run one job at a
 * time. Reads never queue.
 */
class JobQueue(private val scope: CoroutineScope) {
    enum class Lane { FAST, HEAVY }

    private class Job(val key: String, val work: suspend () -> Any?, val queuedAt: Long) {
        val result = CompletableDeferred<Any?>()
    }

    private class LaneState {
        var running: Job? = null
        val waiting = ArrayDeque<Job>()
        var done = 0
    }

    private val lanes = Lane.entries.associateWith { LaneState() }
    private val byKey = HashMap<String, Job>()
    private var done = 0
    private var failed = 0
    private var coalesced = 0
    private var waitMsMax = 0L

    @Suppress("UNCHECKED_CAST")
    fun <T> run(lane: Lane, key: String, work: suspend () -> T): Deferred<T> = synchronized(this) {
        byKey[key]?.let {
            coalesced++
            return it.result as Deferred<T>
        }
        val job = Job(key, work, System.currentTimeMillis())
        byKey[key] = job
        lanes.getValue(lane).waiting += job
        pump(lane)
        job.result as Deferred<T>
    }

    fun snapshot(): QueueSnapshot = synchronized(this) {
        fun lane(state: LaneState) = LaneSnapshot(state.running?.key, state.waiting.map { it.key }, state.done)
        QueueSnapshot(lane(lanes.getValue(Lane.FAST)), lane(lanes.getValue(Lane.HEAVY)), done, failed, coalesced, waitMsMax)
    }

    // Called with the lock held.
    private fun pump(lane: Lane) {
        val state = lanes.getValue(lane)
        if (state.running != null) return
        val job = state.waiting.removeFirstOrNull() ?: return
        state.running = job
        waitMsMax = maxOf(waitMsMax, System.currentTimeMillis() - job.queuedAt)
        scope.launch {
            val outcome = runCatching { job.work() }
            synchronized(this@JobQueue) {
                if (outcome.isSuccess) {
                    done++
                    state.done++
                } else {
                    failed++
                }
                byKey.remove(job.key)
                state.running = null
                pump(lane)
            }
            outcome.fold(job.result::complete, job.result::completeExceptionally)
        }
    }
}
