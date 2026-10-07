package codeloupe.events

import kotlinx.serialization.Serializable

/** A persisted subscription: events matching [events] are POSTed to [url], signed with the daemon's webhook key. */
@Serializable
data class Webhook(val id: String, val url: String, val events: List<String>, val createdAt: String)
