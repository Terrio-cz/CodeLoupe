package codeloupe.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Something the daemon saw, numbered in order (`seq` survives restarts). `data` is scrubbed of secrets. */
@Serializable
data class Event(val seq: Long, val at: String, val type: String, val data: JsonObject)
