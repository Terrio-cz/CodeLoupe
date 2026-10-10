package codeloupe.ingest

import codeloupe.metrics.LimitHit
import codeloupe.metrics.ToolResult
import codeloupe.metrics.TranscriptParser
import codeloupe.metrics.UsageAt

/** What reading one transcript from [offset] on yielded: the parser it left, and the new results, token counts and limit answers. */
class FileDelta(
    val found: FoundTranscript,
    val ter: String?,
    val parser: TranscriptParser,
    val results: List<ToolResult>,
    val usages: List<UsageAt>,
    val limits: List<LimitHit>,
    val offset: Long,
    val tail: String?,
)
