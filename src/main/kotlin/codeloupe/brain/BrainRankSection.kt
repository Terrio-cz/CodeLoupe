package codeloupe.brain

import kotlinx.serialization.Serializable

/** The ranked entries of one [BrainSection], best first, and how many more matched past the limit. */
@Serializable
data class BrainRankSection(val hits: List<BrainEntry>, val more: Int = 0)
