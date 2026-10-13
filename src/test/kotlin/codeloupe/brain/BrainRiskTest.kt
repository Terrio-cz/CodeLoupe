package codeloupe.brain

import codeloupe.brain.BrainWorld.Companion.BILLING
import codeloupe.brain.BrainWorld.Companion.CHECKOUT
import codeloupe.brain.BrainWorld.Companion.RATES
import codeloupe.brain.BrainWorld.Companion.entry
import codeloupe.brain.BrainWorld.Companion.finding
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The risk map over a fixture code repository and a canned brain: severity and recency weigh, a superseded or covered entry does not count, every score names its entries. */
class BrainRiskTest {
    private val world = BrainWorld()
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    @AfterTest
    fun cleanUp() = world.close()

    private fun risk(files: List<String>, configure: (RiskRequest) -> RiskRequest = { it }) =
        runBlocking { world.service.risk(configure(RiskRequest(world.workspace.toString(), files)), now) }

    @Test
    fun `a file's score is the weight of the findings tied to it, severity times recency, and lists them heaviest first`() {
        world.export(
            entries = listOf(
                entry("f-p2today1", "!P2 total drops the remainder", files = listOf("billing/.../Billing.kt:3"), at = "2026-10-13T12:00"),
                entry("f-p3symbol", "!P3 tax with a rate is integer division", anchors = listOf("@sym:com.example.billing.Billing.tax(Int,Int)"), at = "2026-10-13T12:00"),
                entry("f-old00001", "!P2 older finding on the same file", files = listOf("billing/.../Billing.kt:9"), at = "2026-08-14T12:00"),
                entry("f-undated1", "!P3 undated finding", files = listOf("Billing.kt")).copy(at = null),
                entry("f-fact0001", "+ total adds tax before rounding", kind = "fact", files = listOf("billing/.../Billing.kt:3")),
                entry("f-decide01", "~ keep the division", kind = "decision", files = listOf("billing/.../Billing.kt")),
                entry("f-gone0001", "!P0 retired finding", files = listOf("billing/.../Billing.kt"), superseded = true),
                entry("f-future01", "!P0 written after the day asked about", files = listOf("billing/.../Billing.kt"), at = "2026-10-20T09:00"),
                entry("f-machine1", "!P0 machine finding", files = listOf("billing/.../Billing.kt"), level = "machine"),
                entry("f-other001", "!P1 another file", files = listOf("ui/.../Checkout.kt:5")),
            ),
            findings = listOf(
                finding("TER-8", "!P1 Billing.total drops the remainder (Billing.kt:3)", files = listOf("Billing.kt:3"), symbols = listOf("Billing.total")).copy(at = "2026-10-13T12:00"),
                finding("TER-8", "= FAIL gradle test", line = 2, files = listOf("Billing.kt")).copy(at = "2026-10-13T12:00"),
                finding("TER-8", "x declined: used once", line = 3, files = listOf("Billing.kt")).copy(at = "2026-10-13T12:00"),
            ),
        )
        val billing = risk(listOf(BILLING, RATES)).files.single { it.path == BILLING }
        // P2 3.0 + P1 6.0 + P3 via the symbol anchor 1.0 + P2 aged 60 days 1.5 + P3 undated, one half-life old, 0.5
        assertEquals(12.0, billing.score)
        assertEquals("high", billing.level)
        assertEquals(5, billing.entries.size)
        assertEquals(listOf("tasks/TER-8.md:1", "f-p2today1", "f-old00001", "f-p3symbol", "f-undated1"), billing.entries.map { it.ref })
        assertEquals(listOf("P1", "P2", "P2", "P3", "P3"), billing.entries.map { it.severity })
        assertEquals(listOf(6.0, 3.0, 1.5, 1.0, 0.5), billing.entries.map { it.weight })
        assertEquals(60, billing.entries.first { it.ref == "f-old00001" }.ageDays)
        assertEquals(listOf("symbol"), billing.entries.first { it.ref == "f-p3symbol" }.via)
        assertEquals(listOf("file"), billing.entries.first { it.ref == "f-p2today1" }.via)
        assertEquals("!P2 total drops the remainder", billing.entries.first { it.ref == "f-p2today1" }.text, "whole entries")
    }

    @Test
    fun `a module's score counts what names the module and what is tied to its requested files, each entry once`() {
        world.export(
            entries = listOf(
                entry("f-file0001", "!P2 total drops the remainder", files = listOf("billing/.../Billing.kt:3"), at = "2026-10-13T12:00"),
                entry("f-mod00001", "! billing tests need a fixed clock", kind = "gotcha", anchors = listOf("@mod:billing"), at = "2026-10-13T12:00"),
                entry("f-bare0001", "!P1 bare name finding", files = listOf("Billing.kt:7"), at = "2026-10-13T12:00"),
                entry("f-rates001", "!P0 rates are wrong", files = listOf("tax/.../Rates.kt"), at = "2026-10-13T12:00"),
            ),
        )
        val result = risk(listOf(BILLING, CHECKOUT, RATES))
        val modules = result.modules.associateBy { it.module }
        assertEquals(setOf("billing", "ui", "tax"), modules.keys)
        assertEquals(10.0, modules.getValue("tax").score)
        // the file finding 3.0 + the module's gotcha 1.0 + the bare-name finding 6.0, which names no directory but is tied to the requested Billing.kt
        assertEquals(10.0, modules.getValue("billing").score)
        assertEquals(listOf("f-bare0001", "f-file0001", "f-mod00001"), modules.getValue("billing").entries.map { it.ref })
        assertEquals(listOf(BILLING), modules.getValue("billing").files)
        assertEquals(0.0, modules.getValue("ui").score)
        assertEquals("none", modules.getValue("ui").level)
        assertEquals(10.0, result.max, "the number a pipeline choice reads is the heaviest file")
        assertEquals("medium", result.level)
        assertEquals(RATES, result.files.first().path, "files come heaviest first")
    }

    @Test
    fun `asOf replays a past day and the weights, half-life and thresholds come from the request`() {
        world.export(entries = listOf(entry("f-file0001", "!P2 total drops the remainder", files = listOf("billing/.../Billing.kt:3"), at = "2026-10-01T00:00")))
        assertEquals(3.0 * Math.pow(0.5, 12.5 / 60), risk(listOf(BILLING)).files.single().score, 0.01)
        val before = risk(listOf(BILLING)) { it.copy(asOf = "2026-09-30") }
        assertEquals(0.0, before.files.single().score, "the entry was written the day after")
        assertEquals("none", before.level)
        val heavy = risk(listOf(BILLING)) { it.copy(asOf = "2026-10-01T00:00:00Z", weights = mapOf("P2" to 20.0), medium = 5.0, high = 15.0) }
        assertEquals(20.0, heavy.files.single().score)
        assertEquals("high", heavy.level)
        val slow = risk(listOf(BILLING)) { it.copy(asOf = "2026-10-31T00:00", halfLifeDays = 30.0) }
        assertEquals(1.5, slow.files.single().score, 0.01, "30 days is one half-life of 30")
    }

    @Test
    fun `the heaviest entries are listed up to the limit and the rest is counted`() {
        world.export(entries = (1..5).map { entry("f-many000$it", "!P3 finding $it", files = listOf("billing/.../Billing.kt:$it"), at = "2026-10-13T12:00") })
        val file = risk(listOf(BILLING)) { it.copy(limit = 2) }.files.single()
        assertEquals(5.0, file.score)
        assertEquals(2, file.entries.size)
        assertEquals(3, file.more)
    }

    @Test
    fun `a file the code index does not know is still scored by the files the entries name, and a bad request is refused`() {
        world.export(entries = listOf(entry("f-file0001", "!P2 total drops the remainder", files = listOf("billing/.../Billing.kt:3"), at = "2026-10-13T12:00")))
        val result = risk(listOf("billing/src/main/kotlin/com/example/billing/Gone.kt", "billing/src/main/kotlin/com/example/billing/Billing.kt"))
        assertEquals(0.0, result.files.single { it.path.endsWith("Gone.kt") }.score)
        assertEquals(3.0, result.files.single { it.path.endsWith("Billing.kt") }.score)
        assertTrue(result.notes.any { "1 of 2" in it }, result.notes.toString())
        assertFailsWith<IllegalArgumentException> { risk(emptyList()) }
        assertContains(assertFailsWith<IllegalArgumentException> { risk(listOf(BILLING)) { it.copy(asOf = "last week") } }.message.orEmpty(), "asOf")
    }

    @Test
    fun `severity is read from the line the way the engine writes it`() {
        fun sev(text: String, kind: String = "finding", level: String = "workspace") = BrainRisk.severity(entry(null, text, kind = kind, level = level))
        assertEquals("P0", sev("!P0 data loss"))
        assertEquals("x", sev("x fixed in the task"))
        assertEquals("gotcha", sev("! blocker that recurs", kind = "gotcha"))
        assertEquals(null, sev("+ a fact", kind = "fact"))
        assertEquals(null, sev("!P1 machine level", level = "machine"))
        assertEquals("P2", BrainRisk.severity(finding("TER-1", "!P2 a task line")))
        assertEquals(null, BrainRisk.severity(finding("TER-1", "x declined with a reason")))
        assertEquals(null, BrainRisk.severity(finding("TER-1", "= FAIL gradle")))
    }
}
