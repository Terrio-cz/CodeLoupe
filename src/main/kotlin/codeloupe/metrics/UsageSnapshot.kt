package codeloupe.metrics

/**
 * One limit of a `/usage` report Claude Code wrote into a transcript: the meter ([kind] `session`, `weekly_all` or `weekly_scoped`,
 * [scope] the model of a scoped one, else empty), how full it is, when its window ends and how loud Claude Code calls it. [atMs] is
 * when the report was written.
 */
data class UsageSnapshot(
    val atMs: Long,
    val kind: String,
    val group: String?,
    val scope: String,
    val percent: Double?,
    val resetsAtMs: Long?,
    val severity: String?,
    val active: Boolean?,
)
