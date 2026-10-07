package codeloupe.daemon

import kotlinx.serialization.Serializable

@Serializable
data class QueueSnapshot(
    val fast: LaneSnapshot,
    val heavy: LaneSnapshot,
    val done: Int,
    val failed: Int,
    val coalesced: Int,
    val waitMsMax: Long,
)
