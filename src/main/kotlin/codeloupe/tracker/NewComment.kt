package codeloupe.tracker

/** A comment the tracker just stored and the `updated` it gave the issue for it; null when the reply did not say. */
data class NewComment(val comment: IssueComment, val issueUpdated: Long?)
