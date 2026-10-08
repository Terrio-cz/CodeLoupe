package codeloupe.hooks

/** An indexed source file: its absolute [path], the [relative] path in its worktree, and its size in [lines]. */
data class SourceFile(val path: String, val relative: String, val lines: Int)
