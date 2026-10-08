package codeloupe.daemon

import codeloupe.config.BudgetsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryTest {
    @Test
    fun `an empty window reports nothing`() {
        assertEquals(CallLatency(), CallWindow().snapshot())
    }

    @Test
    fun `percentiles use the nearest rank, rates count busy and empty answers`() {
        val window = CallWindow()
        for (ms in 1..100L) window.record("find", ms, chars = ms.toInt(), busy = ms <= 5, empty = ms in 6..15)
        val latency = window.snapshot()
        assertEquals(100, latency.window)
        assertEquals(50, latency.p50Ms)
        assertEquals(95, latency.p95Ms)
        assertEquals(95, latency.p95Chars)
        assertEquals(0.05, latency.busyRate)
        assertEquals(0.1, latency.emptyRate)
        assertEquals(ToolLatency(100, 50, 95), latency.byTool.getValue("find"))
    }

    @Test
    fun `the window keeps the last thousand calls and per-tool figures stay apart`() {
        val window = CallWindow()
        repeat(1000) { window.record("usages", 5000, 10, busy = false, empty = false) }
        repeat(1000) { window.record("find", 2, 10, busy = false, empty = false) }
        window.record("symbol", 40, 10, busy = false, empty = false)
        val latency = window.snapshot()
        assertEquals(1000, latency.window)
        assertEquals(setOf("find", "symbol"), latency.byTool.keys, "the slow calls rolled out")
        assertEquals(2, latency.p50Ms)
        assertEquals(999, latency.byTool.getValue("find").calls)
    }

    @Test
    fun `budgets warn on what exceeds them, and judge latency only with enough calls`() {
        val budgets = BudgetsConfig(p95Ms = 500, queueWaitMs = 10_000, rssMb = 200, busyRate = 0.1)
        val slow = CallLatency(window = 50, p95Ms = 900, busyRate = 0.3)
        val state = BudgetState.check(budgets, slow, rssMb = 300, queueWaitMs = 20_000)
        assertEquals(false, state.ok)
        assertEquals(4, state.warnings.size)
        assertTrue(state.warnings.first().startsWith("p95 latency 900 ms exceeds 500 ms"))
        assertEquals(BudgetState(true), BudgetState.check(budgets, CallLatency(window = 5, p95Ms = 9_000, busyRate = 1.0), rssMb = 150, queueWaitMs = 0))
        assertEquals(BudgetState(true), BudgetState.check(budgets, CallLatency(), rssMb = null, queueWaitMs = 0))
    }

    @Test
    fun `budgets come from config json, bad values fall back`() {
        val file = Json.parseToJsonElement("""{"budgets": {"p95Ms": 250, "rssMb": "180", "busyRate": -1, "queueWaitMs": "x"}}""").jsonObject
        assertEquals(BudgetsConfig(p95Ms = 250, rssMb = 180), BudgetsConfig.parse(file))
        assertEquals(BudgetsConfig(), BudgetsConfig.parse(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun `history samples at most once a minute and keeps the last 240`() {
        var now = Instant.parse("2026-10-08T10:00:00Z")
        val history = ResourceHistory { now }
        history.sample()
        now = now.plusSeconds(30)
        history.sample()
        assertEquals(1, history.list().size)
        repeat(300) {
            now = now.plusSeconds(60)
            history.sample()
        }
        val kept = history.list()
        assertEquals(240, kept.size)
        assertTrue(kept.last().t.startsWith("2026-10-08T15:00:30"), kept.last().t)
        assertTrue(kept.all { it.heapMb >= 0 })
    }
}
