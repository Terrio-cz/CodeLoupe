package codeloupe.daemon

import kotlinx.serialization.Serializable

@Serializable
data class CallStats(val total: Int, val errors: Int, val busy: Int)
