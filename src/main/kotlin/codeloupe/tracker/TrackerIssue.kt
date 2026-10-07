package codeloupe.tracker

import kotlinx.serialization.Serializable

/**
 * An issue as every tracker adapter delivers it; times are epoch milliseconds. The planning fields (state, type,
 * priority, assignee) are filled by the adapter from whatever the tracker calls them and are not repeated in [fields].
 */
@Serializable
data class TrackerIssue(
    val id: String,
    val project: String,
    val summary: String,
    val description: String = "",
    val created: Long,
    val updated: Long,
    /** When the issue reached a resolved state; null while open. */
    val resolved: Long? = null,
    val reporter: String? = null,
    val state: String? = null,
    val type: String? = null,
    val priority: String? = null,
    /** Position of [priority] in the tracker's own order, most urgent first; null when unknown. */
    val priorityRank: Int? = null,
    val assignee: String? = null,
    val fields: List<FieldValue> = emptyList(),
    val links: List<IssueLink> = emptyList(),
    val comments: List<IssueComment> = emptyList(),
    val attachments: List<IssueAttachment> = emptyList(),
) {
    val parent: String? get() = links.firstOrNull { it.kind == LinkKind.PARENT }?.other
}
