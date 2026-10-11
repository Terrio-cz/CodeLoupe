package codeloupe.brain

import kotlinx.serialization.Serializable

/** How often an entry was briefed to a task and in how many of those tasks the review later raised the same issue. */
@Serializable
data class BrainFeedback(val shown: Int = 0, val hit: Int = 0)
