package codeloupe.tracker.mirror

/** Sync bookkeeping of one mirrored project; times are epoch milliseconds, null = never. */
data class ProjectState(
    val project: String,
    val syncedAt: Long?,
    /** Last full id listing that removed deleted or moved issues. */
    val checkedAt: Long?,
    /** Newest `updated` the tracker listed in a sync; the next sync asks from here (minus a margin). */
    val watermark: Long?,
    val issues: Int,
    val error: String?,
)
