package codeloupe.ingest

/** One tool call of a run; [carried] is chars times the turns that followed, [weighted] what keeping the result in context costs. */
data class StepRecord(
    val seq: Int,
    val turn: Int,
    val atMs: Long?,
    val tool: String,
    val category: String,
    val summary: String,
    val chars: Int,
    val durationMs: Long,
    val error: Boolean,
    val errorText: String?,
    val carried: Long,
    val weighted: Long,
    /** The kind of gap this call ended in, if it did. */
    val gap: String?,
)
