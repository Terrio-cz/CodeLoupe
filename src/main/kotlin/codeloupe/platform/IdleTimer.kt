package codeloupe.platform

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs [onIdle] once, [delayMs] after the last [touch]. A one-shot task, not a poll: nothing runs while the daemon
 * is idle beyond that single call.
 */
class IdleTimer(private val delayMs: Long, private val onIdle: () -> Unit) {
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    fun touch() {
        pending?.cancel(false)
        pending = SCHEDULER.schedule({ runCatching(onIdle) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val SCHEDULER = ScheduledThreadPoolExecutor(1) { Thread(it, "codeloupe-idle").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
        }
    }
}
