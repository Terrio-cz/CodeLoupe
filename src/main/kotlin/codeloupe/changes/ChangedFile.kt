package codeloupe.changes

/** A source file that differs from the merge-base: [status] A added, M modified, D deleted (T type change as M). */
data class ChangedFile(val path: String, val status: Char)
