package codeloupe.ingest

import codeloupe.metrics.LimitHit
import codeloupe.metrics.ToolResult
import codeloupe.metrics.TranscriptParser
import codeloupe.metrics.UsageAt
import codeloupe.metrics.UsageSnapshot

/**
 * What reading one transcript from [offset] on yielded: the parser it left, and the new results, token counts, limit answers and `/usage`
 * snapshots. [account] is the account the file's location names and [identity] the account fingerprint the transcript itself named in
 * this read; either is null when the data does not say.
 */
class FileDelta(
    val found: FoundTranscript,
    val ter: String?,
    val parser: TranscriptParser,
    val results: List<ToolResult>,
    val usages: List<UsageAt>,
    val limits: List<LimitHit>,
    val offset: Long,
    val tail: String?,
    val snapshots: List<UsageSnapshot> = emptyList(),
    val account: String? = null,
    val identity: String? = null,
)
