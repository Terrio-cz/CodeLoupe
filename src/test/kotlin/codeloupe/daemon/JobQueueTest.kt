package codeloupe.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JobQueueTest {
    @Test
    fun `same key coalesces, one job per lane at a time, failures reach every caller`() = runBlocking(Dispatchers.Default) {
        val queue = JobQueue(this)
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        suspend fun work(value: Int): Int {
            synchronized(this@JobQueueTest) { running++; maxRunning = maxOf(maxRunning, running) }
            gate.await()
            synchronized(this@JobQueueTest) { running-- }
            return value
        }
        val a = queue.run(JobQueue.Lane.HEAVY, "build:a") { work(1) }
        val sameA = queue.run(JobQueue.Lane.HEAVY, "build:a") { work(99) }
        val b = queue.run(JobQueue.Lane.HEAVY, "build:b") { work(2) }
        assertEquals(LaneSnapshot("build:a", listOf("build:b")), queue.snapshot().heavy)
        gate.complete(Unit)
        assertEquals(listOf(1, 1, 2), listOf(a, sameA, b).awaitAll())
        assertEquals(1, maxRunning)
        val failing = queue.run(JobQueue.Lane.FAST, "x") { error("boom") }
        assertFailsWith<IllegalStateException> { failing.await() }
        val stats = queue.snapshot()
        assertEquals(listOf(2, 1, 1), listOf(stats.done, stats.failed, stats.coalesced))
    }
}
