package codeloupe.repo

/** What a query read from an index, plus a note when the worktree has moved past the indexed commit. */
data class Answer<T>(val value: T, val note: String?)
