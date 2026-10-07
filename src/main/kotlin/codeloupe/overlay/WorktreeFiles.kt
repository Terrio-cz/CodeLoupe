package codeloupe.overlay

/** What a walk of a worktree found: stamps of its indexable sources, and of the `.gitignore` files that decide what git ignores. */
data class WorktreeFiles(val sources: Map<String, Stamp>, val ignoreFiles: Map<String, Stamp>)
