package codeloupe.daemon

import kotlinx.serialization.Serializable

@Serializable
data class LaneSnapshot(val running: String?, val waiting: List<String>)
