package codeloupe.workspace

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class RecentScanTest {
    private var now = 0L
    private val scans = AtomicInteger()

    private fun scan(): WorkspaceList = WorkspaceList("scan-${scans.incrementAndGet()}", emptyList())

    @Test
    fun `reads inside the window share one scan and the next one after it starts a new scan`() = runBlocking {
        val recent = RecentScan(2_000) { now }
        val first = recent.get { scan() }
        now = 1_999
        assertSame(first, recent.get { scan() })
        now = 2_000
        assertNotEquals(first.generatedAt, recent.get { scan() }.generatedAt)
        assertEquals(2, scans.get())
    }

    @Test
    fun `reads that arrive while a scan runs wait for it instead of scanning again`() = runBlocking {
        val recent = RecentScan(2_000) { now }
        val answers = (1..4).map { async { recent.get { delay(100); scan() } } }.awaitAll()
        assertEquals(1, scans.get())
        assertEquals(1, answers.map { it.generatedAt }.toSet().size)
    }

    @Test
    fun `an invalidated scan is dropped, and so is one that was running when it happened`() = runBlocking {
        val recent = RecentScan(60_000) { now }
        recent.get { scan() }
        recent.invalidate()
        assertEquals("scan-2", recent.get { scan() }.generatedAt)

        recent.invalidate()
        val running = async { recent.get { delay(100); scan() } }
        delay(30)
        recent.invalidate()
        assertEquals("scan-3", running.await().generatedAt, "the reader that started it still gets its answer")
        assertEquals("scan-4", recent.get { scan() }.generatedAt, "but nobody else is served from it")
    }

    @Test
    fun `a window of zero shares nothing`() = runBlocking {
        val recent = RecentScan(0) { now }
        recent.get { scan() }
        recent.get { scan() }
        assertEquals(2, scans.get())
    }
}
