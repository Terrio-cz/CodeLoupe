package codeloupe.tracker

/** Tracker part of `config.json`: the instances and how the watcher paces itself. */
data class TrackerSettings(
    val instances: List<TrackerInstance>,
    /** Pause between two syncs of a project while clients are active. */
    val syncMs: Long = 3 * 60_000,
    /** No tool call for this long stops the watcher until the next call. */
    val idleMs: Long = 10 * 60_000,
    /** A read within this long of the project's last sync skips the per-issue freshness check. */
    val freshMs: Long = 30_000,
    /** Entries of the config that were skipped, for the daemon log. */
    val problems: List<String> = emptyList(),
)
