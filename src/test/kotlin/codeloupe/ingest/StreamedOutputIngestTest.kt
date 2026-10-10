package codeloupe.ingest

import codeloupe.metrics.TranscriptBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** CL-206: a streamed message is written on several lines and its output is the final count, however the file is read. */
class StreamedOutputIngestTest {
    private val builder = TranscriptBuilder()
        .prompt("work on TER-7")
        .streamed(listOf(8, 8, 457))
        .streamed(listOf(5, 266))
        .turn(output = 40)

    @Test
    fun `a transcript that grew inside a streamed message holds the final output, as one read at once`() {
        IngestRig().use { rig ->
            val lines = Files.readAllLines(builder.write(rig.root.resolve("whole.jsonl")))
            val target = rig.project.resolve("s1.jsonl")
            Files.createDirectories(target.parent)

            // Cut after the first line of the second message: the saved state must remember what was counted for it.
            val cut = lines.indexOfFirst { it.contains("\"output_tokens\":5") } + 1
            Files.writeString(target, lines.take(cut).joinToString("\n") + "\n")
            rig.ingest.passNow()
            assertEquals(457L + 5, rig.runs().single().usage.output)

            Files.writeString(target, lines.joinToString("\n") + "\n")
            rig.ingest.passNow()
            val run = rig.runs().single()
            assertEquals(457L + 266 + 40, run.usage.output)
            assertEquals(3, run.turns)
            assertEquals(run.weighted, rig.scalar("SELECT sum(cost) FROM usage_hours"), "the hourly cost holds the grown output too")
        }
    }

    @Test
    fun `runs stored by an older parser are read again and their gaps are not announced as new`() {
        IngestRig().use { rig ->
            builder.write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            rig.db.transaction { c ->
                c.createStatement().use { it.executeUpdate("UPDATE runs SET output = 1, cost = 1") }
                rig.db.setMeta(c, "parser", "1")
            }
            rig.ingest.passNow()

            val run = rig.runs().single()
            assertEquals(457L + 266 + 40, run.usage.output)
            assertEquals(emptyList(), rig.eventTypes())
        }
    }
}
