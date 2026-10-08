package codeloupe.ingest

/** A stored gap of a CodeLoupe call (docs/plan.md § 5, `codeloupe metrics gaps`) with the run it happened in. */
data class GapRecord(
    val id: Long,
    val runId: Long,
    val session: String,
    val seq: Int,
    val turn: Int,
    val atMs: Long,
    val tool: String,
    val shape: String,
    val kind: String,
    val token: String?,
    val fallback: String?,
)
