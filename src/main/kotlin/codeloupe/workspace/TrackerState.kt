package codeloupe.workspace

import kotlinx.serialization.Serializable

/** The task of a workspace as the tracker mirror holds it. */
@Serializable
data class TrackerState(val state: String?, val resolved: Boolean, val summary: String)
