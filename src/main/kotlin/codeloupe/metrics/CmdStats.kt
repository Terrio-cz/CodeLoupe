package codeloupe.metrics

import kotlinx.serialization.Serializable

/** One shell command key: how often it ran and what its results cost. */
@Serializable
data class CmdStats(val calls: Int = 0, val attr: Long = 0)
