package codeloupe.repo

import kotlinx.serialization.Serializable

@Serializable
data class LastBuild(val at: String, val ok: Boolean, val files: Int, val errors: Int, val ms: Long, val peakRssMb: Long? = null)
