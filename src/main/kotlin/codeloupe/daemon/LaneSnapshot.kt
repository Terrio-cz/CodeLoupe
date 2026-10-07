package codeloupe.daemon

import kotlinx.serialization.Serializable

/** One lane of the job queue; [done] counts its jobs that succeeded. */
@Serializable
data class LaneSnapshot(val running: String?, val waiting: List<String>, val done: Int = 0)
