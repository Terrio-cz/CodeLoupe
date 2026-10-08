package codeloupe.metrics

/** One transcript read: a subagent run, a phase-mode run or a desktop session (role `main`). */
data class Run(
    val file: String,
    val kind: String,
    val role: String,
    val ter: String?,
    val model: String?,
    val start: String?,
    val end: String?,
    val turns: Int,
    val usage: Usage,
    val peakContext: Long,
    val tools: List<ToolCall>,
) {
    val wallSec: Long
        get() = if (start != null && end != null) Math.round((java.time.Instant.parse(end).toEpochMilli() - java.time.Instant.parse(start).toEpochMilli()) / 1000.0) else 0
}
