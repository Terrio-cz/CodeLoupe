package codeloupe.changes

/** How one declaration changed between two versions of a file; [before] is null when added, [after] when removed. */
data class DeclChange(val mark: Char, val before: DeclVersion?, val after: DeclVersion?) {
    /** The version a reader looks at now: the new one, or the removed one. */
    val current: DeclVersion get() = after ?: before!!

    companion object {
        const val ADDED = '+'
        const val BODY = '~'
        const val SIGNATURE = '^'
        const val REMOVED = '-'
    }
}
