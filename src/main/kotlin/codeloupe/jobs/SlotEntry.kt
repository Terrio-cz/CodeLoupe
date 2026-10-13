package codeloupe.jobs

import kotlinx.serialization.Serializable

/** A job holding or waiting for a slot: since when, and for how many seconds by now. */
@Serializable
data class SlotEntry(val id: String, val since: String, val seconds: Long)
