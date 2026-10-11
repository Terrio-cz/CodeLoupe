package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** One topic's entries, in file order. [Entry.stamp] is the date or note the brain recorded after the entry. */
@Serializable
data class BrainTopicView(val workspaceId: String, val topic: String, val bytes: Long, val entries: List<Entry>) {
    @Serializable
    data class Entry(val mark: String? = null, val text: String, val stamp: String? = null)
}
