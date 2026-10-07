package codeloupe.tracker

import kotlinx.serialization.Serializable

@Serializable
data class IssueComment(val id: String, val author: String?, val created: Long, val updated: Long?, val text: String)
