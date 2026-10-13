package codeloupe.index

import codeloupe.lang.FileFacts
import kotlinx.serialization.Serializable

/**
 * The worker's answer to the request of the same [id]: the facts of its files, in their order. [parseMs] is how long the parse
 * took in the worker, so that the daemon can tell a worker's start from its first parse (CL-251).
 */
@Serializable
data class ParseReply(val id: Long, val facts: List<FileFacts>, val parseMs: Long = 0)
