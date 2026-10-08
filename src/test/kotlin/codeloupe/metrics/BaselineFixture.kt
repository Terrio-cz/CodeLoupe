package codeloupe.metrics

import codeloupe.JsonFormat
import java.nio.file.Files
import java.nio.file.Path

/** A baseline report with only what the daemon reads: the number of runs of each role and their summed cost. */
object BaselineFixture {
    private val NONE = Pick(0, 0, 0)

    fun report(label: String = "baseline", vararg roles: Triple<String, Int, Long>): MetricsReport = MetricsReport(
        label, "2026-09-23", "2026-10-06", "2026-10-07T00:00:00Z", MetricsReport.WEIGHTS,
        roles.associate { (role, runs, cost) ->
            role to RoleAggregate(runs, Pick(cost / runs, cost / runs, cost), NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, 0.0, emptyList(), emptyMap())
        },
        emptyList(),
    )

    fun write(file: Path, report: MetricsReport): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, JsonFormat.json.encodeToString(MetricsReport.serializer(), report))
        return file
    }
}
