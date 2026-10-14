package codeloupe.daemon

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Hands back the heap the serial collector grew. Young collections promote short-lived garbage, and the old generation
 * grows towards -Xmx rather than being collected, so a daemon with 25-35 MB live held a 247 MB old generation and 390 MB
 * resident (CL-238). Only a full collection shrinks it to the live set plus `MaxHeapFreeRatio`.
 *
 * [touch] on activity schedules one check [delayMs] later; touches while it is pending add nothing, so an active daemon
 * checks at most once per [delayMs] and an idle one stops after a single check. The check collects only when the committed
 * heap grew more than [slackMb] since the last collection it made: a full collection costs a pause of tens of milliseconds.
 */
class HeapTrimmer(
    private val delayMs: Long = DELAY_MS,
    private val slackMb: Long = SLACK_MB,
    private val committedMb: () -> Long = { Runtime.getRuntime().totalMemory() / MB },
    private val collect: () -> Unit = System::gc,
) {
    private var pending: ScheduledFuture<*>? = null
    private var settledMb = 0L

    @Synchronized
    fun touch() {
        if (pending?.isDone == false) return
        pending = SCHEDULER.schedule({ runCatching { check() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Collects when the heap grew past the slack; true when it did. */
    @Synchronized
    fun check(): Boolean {
        if (committedMb() <= settledMb + slackMb) return false
        collect()
        settledMb = committedMb()
        return true
    }

    private companion object {
        const val DELAY_MS = 60_000L
        const val SLACK_MB = 32L
        const val MB = 1024L * 1024
        val SCHEDULER = ScheduledThreadPoolExecutor(1) { Thread(it, "codeloupe-heap-trim").apply { isDaemon = true } }
    }
}
