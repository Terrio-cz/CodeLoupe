package codeloupe.metrics

import kotlinx.serialization.Serializable

@Serializable
data class TopCommand(val cmd: String, val calls: Int, val costPct: Double)
