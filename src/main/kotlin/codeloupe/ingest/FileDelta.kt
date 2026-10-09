package codeloupe.ingest

import codeloupe.metrics.ToolResult
import codeloupe.metrics.TranscriptParser
import codeloupe.metrics.UsageAt

/** What reading one transcript from [offset] on yielded: the parser it left, and the new results and token counts. */
class FileDelta(
    val found: FoundTranscript,
    val ter: String?,
    val parser: TranscriptParser,
    val results: List<ToolResult>,
    val usages: List<UsageAt>,
    val offset: Long,
    val tail: String?,
)
