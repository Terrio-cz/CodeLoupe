package codeloupe.uiapi

import codeloupe.ingest.HourUsage
import codeloupe.metrics.Baseline
import codeloupe.metrics.BaselineFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavingsTest {
    private val baseline = Baseline.of(BaselineFixture.report("b", Triple("reviewer", 2, 1_000), Triple("coder", 1, 400)))
    private val now = 1_000_000L

    private fun row(hour: Long, role: String, cost: Double, runCost: Long, endMs: Long = 0) = HourUsage(hour, role, cost, runCost, endMs)

    @Test
    fun `a finished run counts the mean baseline cost of its role, spread over its hours like its real cost`() {
        // A reviewer run of 300 over two hours, 100 + 200; the mean reviewer run cost is 500.
        val savings = Savings(listOf(row(1, "reviewer", 100.0, 300), row(2, "reviewer", 200.0, 300)), baseline, now)
        val hours = savings.baselineHours()
        assertEquals(500.0 / 3, hours.getValue(1), 1e-9)
        assertEquals(1000.0 / 3, hours.getValue(2), 1e-9)
        val all = savings.from(0)
        assertEquals(500.0, all.baseline, 1e-9)
        assertEquals(300.0, all.actual, 1e-9)
        assertEquals(40.0, all.savedPct)
        assertEquals(1.0, all.coveredShare)
    }

    @Test
    fun `a role without a baseline and a run still going count their real cost on both sides`() {
        val savings = Savings(
            listOf(row(1, "reviewer", 250.0, 250), row(1, "planner", 700.0, 700), row(1, "coder", 90.0, 90, endMs = now + 1)),
            baseline, now,
        )
        val window = savings.from(0)
        assertEquals(500.0 + 700.0 + 90.0, window.baseline, 1e-9)
        assertEquals(250.0 + 700.0 + 90.0, window.actual, 1e-9)
        assertEquals(250.0 / (250.0 + 700.0 + 90.0), window.coveredShare, 1e-9)
        assertEquals(50.0, window.savedPct, "250 against 500 on the one run that could be compared")
    }

    @Test
    fun `a run that cost more than the baseline gives a negative saving, and nothing comparable gives none`() {
        assertEquals(-20.0, Savings(listOf(row(1, "reviewer", 600.0, 600)), baseline, now).from(0).savedPct)
        assertNull(Savings(listOf(row(1, "planner", 600.0, 600)), baseline, now).from(0).savedPct)
        assertNull(Savings(emptyList(), baseline, now).from(0).savedPct)
    }

    @Test
    fun `a window starts at its first hour`() {
        val savings = Savings(listOf(row(1, "reviewer", 100.0, 100), row(5, "reviewer", 500.0, 500)), baseline, now)
        assertEquals(500.0, savings.from(5).baseline, 1e-9)
        assertEquals(0.0, savings.from(5).savedPct)
    }
}
