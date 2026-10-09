package codeloupe.metrics

import codeloupe.JsonFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetricsReportDecodeTest {
    @Test
    fun `a report of the workspace script without since and until is read`() {
        val json = """{"label":"baseline","generated":"2026-10-01T00:00:00Z","weights":{"input":1.0},"aggregate":{},"runs":[]}"""
        val report = JsonFormat.json.decodeFromString(MetricsReport.serializer(), json)
        assertEquals("baseline", report.label)
        assertNull(report.since)
        assertNull(report.until)
    }
}
