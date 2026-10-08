package codeloupe.ingest

import java.nio.file.Path

/** A transcript file on disk: a [kind] `session`, or a `subagent` run of the session named [session]. */
data class FoundTranscript(val path: Path, val kind: String, val project: String, val session: String, val size: Long, val mtime: Long) {
    val key: String get() = path.toAbsolutePath().normalize().toString()
}
