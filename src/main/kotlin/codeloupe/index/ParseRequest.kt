package codeloupe.index

import kotlinx.serialization.Serializable

/** One line to a [ParseWorker]: the files to parse, together. */
@Serializable
data class ParseRequest(val id: Long, val files: List<ParseFile>)
