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
)
