package codeloupe.metrics

import kotlinx.serialization.Serializable

/** Reads of code files in a run: [rereads] are repeat reads of a file, [whole] reads without offset or limit. */
@Serializable
data class CodeReads(val calls: Int, val files: Int, val rereads: Int, val whole: Int, val chars: Long)
