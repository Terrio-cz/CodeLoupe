package codeloupe.git

/** A worktree's root and the git common dir it shares with the repository's other worktrees, as real paths. */
data class WorktreeDirs(val worktree: String, val commonDir: String)
