package codeloupe.ingest

import codeloupe.metrics.Usage

/** One agent run as the ingest stored it; [weighted] is its cost in weighted tokens, [share] the part of it that tool results carry (0..1000). */
data class RunRecord(
    val id: Long,
    val file: String,
    val session: String,
    val project: String,
    val kind: String,
    val role: String,
    val ter: String?,
    val model: String?,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val durationSec: Long,
    val turns: Int,
    val usage: Usage,
    val weighted: Long,
    val peakContext: Long,
    val toolCalls: Int,
    val toolErrors: Int,
    val toolSec: Long,
    val share: Int,
)
