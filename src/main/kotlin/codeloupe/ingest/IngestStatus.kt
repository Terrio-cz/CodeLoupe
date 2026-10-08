package codeloupe.ingest

import kotlinx.serialization.Serializable

/** How far the transcript ingest is: [running] while files are read, [filesDone] of [filesTotal] changed files of the pass; [at] when the last pass ended. */
@Serializable
data class IngestStatus(val running: Boolean, val filesDone: Int, val filesTotal: Int, val at: String?)
