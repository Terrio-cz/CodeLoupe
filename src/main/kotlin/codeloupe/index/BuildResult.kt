package codeloupe.index

import kotlinx.serialization.Serializable

/** The one JSON line a build worker prints. */
@Serializable
data class BuildResult(
    val ok: Boolean,
    val files: Int = 0,
    val errors: Int = 0,
    val ms: Long = 0,
    val peakRssMb: Long? = null,
    val error: String? = null,
    /** Files of an update that could not be read and were left unchanged. */
    val unread: List<String> = emptyList(),
    /** Files of an update whose facts came from a store holding the same content, not from a parse. */
    val reused: Int = 0,
)
