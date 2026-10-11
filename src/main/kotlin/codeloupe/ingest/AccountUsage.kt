package codeloupe.ingest

import java.nio.file.Path

/** What one Claude account used: the runs of its [RunScope]. */
class AccountUsage(private val queries: RunQueries) {
    data class Totals(val weighted: Long, val lastUsedMs: Long?)

    /** Weighted tokens from [sinceMs] on (by hour bucket, like the Overview) and the end of the newest run, ever. */
    fun totals(scope: RunScope, sinceMs: Long): Totals {
        val weighted = Math.round(queries.hours(Math.floorDiv(sinceMs, RunWriter.HOUR_MS), Long.MAX_VALUE, scope).values.sum())
        return Totals(weighted, queries.lastEndMs(scope))
    }

    companion object {
        /** The transcript paths of the ingest are absolute with the OS separator; a prefix ends in one, so `.claude` never matches `.claude-b`. */
        fun prefix(projects: Path): String = projects.toAbsolutePath().normalize().toString().trimEnd('/', '\\') + java.io.File.separator
    }
}
