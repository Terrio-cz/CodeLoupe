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
            // Waits poll instead of sleeping a fixed time: a loaded CI runner can stall a thread for hundreds of ms.
            assertTrue(until { watcher.touch(); runs.get() >= 2 }, "${runs.get()} runs while calls arrive")
            assertTrue(watcher.running)
            assertTrue(until { !watcher.running }, "idle: the loop ended")
            val idle = runs.get()
            Thread.sleep(300)
            assertEquals(idle, runs.get(), "no work while idle")
            watcher.touch()
            assertTrue(until { runs.get() > idle }, "the next call starts it again")
        } finally {
            scope.cancel()
        }
    }

    private fun until(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return false
    }
}
