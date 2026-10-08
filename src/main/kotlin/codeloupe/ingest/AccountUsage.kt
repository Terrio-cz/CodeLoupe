package codeloupe.ingest

import java.nio.file.Path

/** What one Claude account used: the runs whose transcript lies under its config directory's `projects` folder. */
class AccountUsage(private val queries: RunQueries) {
    data class Totals(val weighted: Long, val lastUsedMs: Long?)

    /** Weighted tokens from [sinceMs] on (by hour bucket, like the Overview) and the end of the newest run, ever. */
    fun totals(projects: Path, sinceMs: Long): Totals {
        val prefix = prefix(projects)
        val weighted = Math.round(queries.hours(Math.floorDiv(sinceMs, RunWriter.HOUR_MS), Long.MAX_VALUE, prefix).values.sum())
        return Totals(weighted, queries.lastEndMs(prefix))
    }

    companion object {
        /** The transcript paths of the ingest are absolute with the OS separator; a prefix ends in one, so `.claude` never matches `.claude-b`. */
        fun prefix(projects: Path): String = projects.toAbsolutePath().normalize().toString().trimEnd('/', '\\') + java.io.File.separator
    }
}
