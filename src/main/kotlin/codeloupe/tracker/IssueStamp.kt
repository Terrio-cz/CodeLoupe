package codeloupe.tracker

/** An issue id with its last update, the cheap answer used to find what changed. */
data class IssueStamp(val id: String, val updated: Long)
