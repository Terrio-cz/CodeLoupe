package codeloupe.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatcherTest {
    @Test
    fun `the watcher syncs while calls arrive and stops when they stop`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runs = AtomicInteger()
        val watcher = Watcher(scope, periodMs = 40, idleMs = 150) { runs.incrementAndGet() }
        try {
            assertFalse(watcher.running)
            assertEquals(0, runs.get(), "nothing runs before the first call")
            watcher.touch()
            Thread.sleep(100)
            assertTrue(watcher.running)
            assertTrue(runs.get() >= 2, "${runs.get()} runs")
            Thread.sleep(400)
            assertFalse(watcher.running, "idle: the loop ended")
            val idle = runs.get()
            Thread.sleep(300)
            assertEquals(idle, runs.get(), "no work while idle")
            watcher.touch()
            Thread.sleep(30)
            // At least one run, not exactly one: a slow runner can fit a second period into the sleep.
            assertTrue(runs.get() >= idle + 1, "the next call starts it again at once: ${runs.get()} runs after $idle")
        } finally {
            scope.cancel()
        }
    }
}
