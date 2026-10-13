package codeloupe.index

import codeloupe.lang.FileFacts
import kotlinx.serialization.Serializable

/** The worker's answer to the request of the same [id]: the facts of its files, in their order. */
@Serializable
data class ParseReply(val id: Long, val facts: List<FileFacts>)
