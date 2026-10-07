package codeloupe.events

/** Event types the daemon emits; subscriptions filter on them (`job.*` matches a prefix). */
object EventTypes {
    const val JOB_STARTED = "job.started"
    const val JOB_FINISHED = "job.finished"
    const val JOB_NOTIFY = "job.notify"
    const val BUILD_DONE = "build.done"
    const val OVERLAY_REFRESHED = "overlay.refreshed"

    val ALL = listOf(JOB_STARTED, JOB_FINISHED, JOB_NOTIFY, BUILD_DONE, OVERLAY_REFRESHED)

    /** Whether [type] matches one of [filters]; no filters match everything. */
    fun matches(filters: List<String>, type: String): Boolean =
        filters.isEmpty() || filters.any { f -> if (f.endsWith("*")) type.startsWith(f.dropLast(1)) else f == type }

    fun validFilter(filter: String): Boolean =
        filter == "*" || filter in ALL || (filter.endsWith(".*") && ALL.any { it.startsWith(filter.dropLast(1)) })
}
