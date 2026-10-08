package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.config.MetricsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money from token counts: two models in one report, an unknown model left out, prices overridden from the configuration. */
class MetricsMoneyTest {
    // One million input and a hundred thousand output tokens per run: round prices make the sums easy to check.
    private fun run(dir: Path, role: String, name: String, model: String) {
        val file = TranscriptBuilder(model = model).prompt("work").turn(input = 1_000_000, output = 100_000, cacheRead = 0, written1h = 0, written5m = 0)
            .write(dir.resolve("project").resolve("s").resolve("subagents").resolve("$name.jsonl"))
        Files.writeString(file.resolveSibling("$name.meta.json"), """{"agentType": "$role", "description": "task"}""")
    }

    private fun report(): MetricsReport {
        val dir = TestRepos.tmpDir("metrics-money")
        run(dir, "steward", "agent-1", "claude-opus-5-5")
        run(dir, "steward", "agent-2", "claude-sonnet-5-5")
        run(dir, "tester", "agent-3", "claude-sonnet-5-5")
        run(dir, "retro", "agent-4", "claude-mystery-9")
        return MetricsCollector(Categorizer(Categorizer.DEFAULT_RULES)) { Instant.parse("2026-10-08T00:00:00Z") }
            .collect(listOf(dir.resolve("project")), "test", Instant.parse("2026-01-01T00:00:00Z"), null)
    }

    @Test
    fun `a run costs its tokens at the price of its model`() {
        val money = MetricsMoney.byRole(report().runs, PriceTable.DEFAULT)
        // Opus 5.5: 1 M x 4 + 0.1 M x 20 = 6; Sonnet 5.5: 1 M x 2 + 0.1 M x 10 = 3.
        assertEquals(9.0, money.getValue("steward").priced, 1e-9)
        assertEquals(3.0, money.getValue("tester").priced, 1e-9)
        assertEquals(0, money.getValue("steward").unpricedRuns)
    }

    @Test
    fun `a model the table does not know is listed, not guessed`() {
        val runs = report().runs
        val retro = MetricsMoney.byRole(runs, PriceTable.DEFAULT).getValue("retro")
        assertEquals(0.0, retro.priced)
        assertEquals(setOf("claude-mystery-9"), retro.unpricedModels)
        val lines = MoneyRender.roleLines(runs, PriceTable.DEFAULT).joinToString("\n")
        assertContains(lines, "retro  0.00 USD  (+1 of 1 runs unpriced)")
        assertContains(lines, "unpriced models, left out: claude-mystery-9")
        assertContains(lines, "in USD at the prices of 2026-10-06")
    }

    @Test
    fun `what-if prices the same tokens on other models and prints the caveat`() {
        val text = MoneyRender.whatIf(report().runs, PriceTable.DEFAULT, listOf("steward", "tester"), listOf("claude-haiku-5-5", "claude-opus-5-5"))
        val steward = text.lines().first { it.startsWith("steward") }
        // On Haiku 5.5 each of the two runs costs 0.1 + 0.05 = 0.15; on Opus 5.5 both cost 6.
        assertContains(steward, "9.00 USD")
        assertContains(steward, "0.30 USD (-97%)")
        assertContains(steward, "12.00 USD (+33%)")
        assertContains(text, MoneyRender.CAVEAT)
        assertContains(text, "unpriced models, left out: claude-mystery-9")
    }

    @Test
    fun `compare shows the money change of each role`() {
        val before = report().runs
        val after = before.filter { it.model != "claude-opus-5-5" }
        val lines = MoneyRender.compare(before, after, PriceTable.DEFAULT)
        assertContains(lines.joinToString("\n"), "steward  9.00 USD -> 3.00 USD (-67%)")
    }

    @Test
    fun `a date suffix is ignored, config prices override and add models and carry their date`() {
        assertEquals(PriceTable.DEFAULT.priceOf("claude-haiku-4-5"), PriceTable.DEFAULT.priceOf("claude-haiku-4-5-20251001"))
        assertNull(PriceTable.DEFAULT.priceOf("claude-mystery-9"))
        assertEquals(0.0, PriceTable.DEFAULT.priceOf("<synthetic>")!!.input)

        val file = Json.parseToJsonElement(
            """{"metrics": {"prices": {"asOf": "2026-11-01", "currency": "EUR", "models": {
                "claude-mystery-9": {"input": 1, "output": 2},
                "claude-opus-5-5": {"input": 6, "output": 30, "cacheRead": 0.5, "cacheWrite5m": 7, "cacheWrite1h": 12}}}}}""",
        ).jsonObject
        val prices = MetricsConfig.parse(file).prices
        assertEquals("2026-11-01", prices.asOf)
        assertEquals("EUR", prices.currency)
        assertEquals(ModelPrice(6.0, 30.0, 0.5, 7.0, 12.0), prices.priceOf("claude-opus-5-5"))
        assertEquals(ModelPrice(1.0, 2.0, 0.1, 1.25, 2.0), prices.priceOf("claude-mystery-9"))
        assertEquals(PriceTable.DEFAULT.priceOf("claude-sonnet-5-5"), prices.priceOf("claude-sonnet-5-5"), "other models keep the defaults")
        assertFalse(MetricsMoney.byRole(report().runs, prices).getValue("retro").unpricedRuns > 0)
        assertTrue(MetricsConfig.parse(Json.parseToJsonElement("""{"metrics": {"prices": {"models": {"x": {"input": 1}}}}}""").jsonObject).prices.priceOf("x") == null)
    }
}
