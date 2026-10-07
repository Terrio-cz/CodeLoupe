package codeloupe.repo

/** Where a path lives: its worktree and the git common dir shared by all worktrees of the repository. */
data class RepoLocation(val worktree: String, val commonDir: String, val at: Long)
