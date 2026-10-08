package codeloupe.git

/** When a commit was made (epoch seconds) and its subject line. */
data class CommitInfo(val timeSec: Long, val subject: String)
