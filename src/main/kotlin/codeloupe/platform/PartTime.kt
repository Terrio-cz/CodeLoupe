package codeloupe.platform

import kotlinx.serialization.Serializable

/** How often a [TimedPart] ran and how long it took in total, in microseconds. */
@Serializable
data class PartTime(val count: Long, val us: Long)
