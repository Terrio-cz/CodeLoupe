package codeloupe.know

import kotlinx.serialization.Serializable

/** The answer to a batch of writes, one result per fact, in order. */
@Serializable
data class PutBatch(val results: List<PutResult>)
