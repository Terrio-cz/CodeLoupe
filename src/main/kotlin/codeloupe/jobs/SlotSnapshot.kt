package codeloupe.jobs

import kotlinx.serialization.Serializable

@Serializable
data class SlotSnapshot(val name: String, val capacity: Int, val running: List<String>, val waiting: List<String>)
