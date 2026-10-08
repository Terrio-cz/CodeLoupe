package codeloupe.metrics

import java.nio.file.Path

/** A transcript to read and what is known about it before reading: [kind] `session` or `subagent`. */
data class TranscriptFile(val path: Path, val kind: String, val role: String, val ter: String? = null)
