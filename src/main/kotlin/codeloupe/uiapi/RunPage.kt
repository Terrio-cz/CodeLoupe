package codeloupe.uiapi

import codeloupe.ingest.IngestStatus
import kotlinx.serialization.Serializable

/** A page of runs; [ingest] says whether the transcripts are still being read, [roles] lists the agent roles seen. */
@Serializable
data class RunPage(val items: List<RunItem>, val total: Int, val nextCursor: String?, val roles: List<String>, val ingest: IngestStatus)
