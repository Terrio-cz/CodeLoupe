package codeloupe.index

import codeloupe.lang.FileFacts
import kotlinx.serialization.Serializable

/** One line to a [ParseWorker]: the file to parse. */
@Serializable
data class ParseRequest(val id: Long, val path: String, val text: String)

/** The worker's answer to the request of the same [id]. */
@Serializable
data class ParseReply(val id: Long, val facts: FileFacts)
