package codeloupe.tracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [work] every [periodMs] while tool calls keep coming. There is no timer while idle: a call starts the
 * loop, and the loop ends once no call arrived for [idleMs]; the next call starts it again.
 */
class Watcher(
    private val scope: CoroutineScope,
    private val periodMs: Long,
    private val idleMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val work: suspend () -> Unit,
) {
    private var lastCall = 0L
    private var job: Job? = null

    val running: Boolean get() = synchronized(this) { job != null }

    fun touch() = synchronized(this) {
        lastCall = clock()
        if (job == null) job = scope.launch { loop() }
    }

    private suspend fun loop() {
        while (true) {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The work reports its own failures; the loop keeps its pace.
            }
            delay(periodMs)
            // Deciding to stop and forgetting the job happen under the same lock as a call's touch.
            synchronized(this) {
                if (clock() - lastCall >= idleMs) {
                    job = null
                    return
                }
            }
        }
    }
}
