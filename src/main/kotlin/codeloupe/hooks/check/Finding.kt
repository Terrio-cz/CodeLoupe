package codeloupe.hooks.check

/** One thing the post-edit check found, at a 1-based [line] and [col] of the edited file. */
data class Finding(val line: Int, val col: Int, val text: String)
