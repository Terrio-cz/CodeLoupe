package codeloupe.tracker

import kotlinx.serialization.Serializable

/** Attachment metadata only; content stays in the tracker. */
@Serializable
data class IssueAttachment(val id: String, val name: String, val size: Long, val mime: String?, val created: Long, val author: String?)
