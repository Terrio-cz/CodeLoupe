package codeloupe.uiapi

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.metrics.GapReport
import codeloupe.metrics.GapRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapViewsTest {
    private val file = TestRepos.tmpDir("gap-views").resolve(GapViews.FILE)

    @Test
    fun `without a report the screen gets none, and no occurrences of its own`() {
        val gaps = GapViews(file).gaps()
        assertNull(gaps.report)
        assertTrue(gaps.summary.isEmpty() && gaps.items.isEmpty())
    }

    @Test
    fun `the report the CLI wrote is served by week, tool and shape with its time and coverage`() {
        val report = GapReport(
            runs = 12, calls = 340,
            rows = listOf(GapRow("2026-W41", "find", "find:glob", "fallback", 4, listOf("*Repository")), GapRow("2026-W42", "symbol", "symbol:name", "empty", 1, emptyList())),
        ).covering("2026-09-10", "2026-10-08T10:00:00.000Z")
        Files.writeString(file, JsonFormat.json.encodeToString(GapReport.serializer(), report))

        val served = GapViews(file).gaps().report!!
        assertEquals(12, served.runs)
        assertEquals(340, served.calls)
        assertEquals("2026-09-10", served.since)
        assertEquals("2026-10-08T10:00:00.000Z", served.generatedAt)
        assertEquals(listOf("2026-W41" to 4, "2026-W42" to 1), served.rows.map { it.week to it.count })
        assertEquals(listOf("*Repository"), served.rows.first().examples)
    }

    @Test
    fun `a report from an older CLI without its time is dated by the file, and an unreadable file is no report`() {
        Files.writeString(file, """{"runs":1,"calls":2,"rows":[]}""")
        assertTrue(GapViews(file).gaps().report!!.generatedAt!!.startsWith("20"))
        Files.writeString(file, "not json")
        assertNull(GapViews(file).gaps().report)
    }

    @Test
    fun `an example that looks like a credential is masked before it leaves the daemon`() {
        val secret = "ghp_" + "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"
        val report = GapReport(1, 1, listOf(GapRow("2026-W41", "find", "find:name", "fallback", 1, listOf(secret))))
        Files.writeString(file, JsonFormat.json.encodeToString(GapReport.serializer(), report))
        assertTrue(secret !in GapViews(file).gaps().report!!.rows.single().examples.single())
    }
}
