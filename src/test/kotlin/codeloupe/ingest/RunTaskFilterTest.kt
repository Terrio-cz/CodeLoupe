package codeloupe.ingest

import codeloupe.metrics.TranscriptBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RunTaskFilterTest {
    private val start = Instant.parse("2026-10-08T10:00:00Z")

    private fun rig(vararg tasks: String): IngestRig = IngestRig().also { rig ->
        tasks.forEachIndexed { i, task -> TranscriptBuilder(start.plusSeconds(i * 60L)).prompt("work on $task").turn().write(rig.project.resolve("s$i.jsonl")) }
        rig.ingest.passNow()
    }

    @Test
    fun `the task filter is exact where the text search is a substring`() {
        rig("TER-1", "TER-114", "TER-12", "TER-1").use { rig ->
            assertEquals(2, rig.queries.runs(0, null, null, RunSort.START, 0, 50, ter = "TER-1").total)
            assertEquals(listOf("TER-1", "TER-1"), rig.queries.runs(0, null, null, RunSort.START, 0, 50, ter = "TER-1").items.map { it.ter })
            assertEquals(4, rig.queries.runs(0, null, "TER-1", RunSort.START, 0, 50).total, "q still matches substrings")
            assertEquals(1, rig.queries.runs(0, null, null, RunSort.START, 0, 50, ter = "TER-114").total)
        }
    }

    @Test
    fun `the task filter ignores case, composes with the other filters and finds nothing for an unknown task`() {
        rig("TER-1", "TER-114").use { rig ->
            assertEquals(1, rig.queries.runs(0, null, null, RunSort.START, 0, 50, ter = "ter-1").total)
            assertEquals(0, rig.queries.runs(0, "terrio-coder", null, RunSort.START, 0, 50, ter = "TER-1").total)
            assertEquals(1, rig.queries.runs(0, "main", "work", RunSort.START, 0, 50, ter = "TER-114").total)
            assertEquals(0, rig.queries.runs(0, null, null, RunSort.START, 0, 50, ter = "TER-9").total)
        }
    }
}
