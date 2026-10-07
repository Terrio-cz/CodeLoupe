package codeloupe.repo

import codeloupe.query.View

/** An open view for one query, plus a note when the worktree has moved past the indexed commit. */
data class RepoView(val view: View, val repo: RepoState, val worktree: String, val note: String?)
