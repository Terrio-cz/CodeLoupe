package codeloupe.ingest

import codeloupe.metrics.TranscriptBuilder
import codeloupe.metrics.TranscriptBuilder.Companion.args
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The size of the baseline (2 651 runs of the Terrio workspace, CL-31), generated: the reads stay far under 200 ms and only new lines are read. */
class IngestScaleTest {
    private fun <T> millis(body: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        return body() to (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `thousands of runs list and expand in milliseconds, and a later pass reads only what grew`() {
        IngestRig(now = Instant.parse("2026-10-08T12:00:00Z")).use { rig ->
            val start = Instant.parse("2026-09-20T08:00:00Z")
            repeat(RUNS) { i ->
                val b = TranscriptBuilder(start.plusSeconds(i * 600L)).prompt("run $i")
                repeat(6) { n ->
                    b.turn(output = 5L + i % 50, tools = arrayOf(Triple("t$n", "Bash", args("command" to "echo step $n of $i"))))
                    b.result("t$n", "r".repeat(100 + n * 50))
                }
                b.write(rig.project.resolve("s$i.jsonl"))
            }
            val (_, ingestMs) = millis { rig.ingest.passNow() }
            assertEquals(RUNS.toLong(), rig.scalar("SELECT count(*) FROM runs"))
            println("ingest of $RUNS runs: $ingestMs ms")

            val from = Instant.parse("2026-09-08T00:00:00Z").toEpochMilli()
            val steps = rig.runs(RunSort.WEIGHTED).first().id
            repeat(3) {
                rig.queries.runs(from, null, null, RunSort.WEIGHTED, 0, 50)
                rig.queries.steps(steps, StepSort.WEIGHTED, 0, 200)
            }
            for (sort in RunSort.entries) {
                val (page, ms) = millis { rig.queries.runs(from, null, null, sort, 0, 50) }
                assertEquals(RUNS, page.total)
                assertTrue(ms < 200, "runs by ${sort.param}: $ms ms")
            }
            val (page, stepsMs) = millis { rig.queries.steps(steps, StepSort.WEIGHTED, 0, 200) }
            assertEquals(6, page.total)
            assertTrue(stepsMs < 200, "steps: $stepsMs ms")

            val (_, idleMs) = millis { rig.ingest.passNow() }
            assertEquals(0, rig.ingest.status().filesTotal, "nothing grew: nothing is read")
            assertTrue(idleMs < 2_000, "a pass over $RUNS unchanged transcripts took $idleMs ms")

            val grown = rig.project.resolve("s7.jsonl")
            val before = Files.size(grown)
            Files.writeString(grown, Files.readString(grown) + Files.readAllLines(grown).first { it.contains("\"type\":\"user\"") && it.contains("tool_result") }.replace("t0", "tx") + "\n")
            rig.ingest.passNow()
            assertEquals(1, rig.ingest.status().filesTotal)
            assertEquals(Files.size(grown), rig.scalar("SELECT offset FROM files WHERE path LIKE '%s7.jsonl'"))
            assertTrue(before < Files.size(grown))
        }
    }

    private companion object {
        const val RUNS = 2_651
    }
}
