package codeloupe.hooks

/** What a shell command does to source code, as far as CodeLoupe has a tool for it. Paths are absolute, forward slashes. */
sealed interface ShellIntent {
    /** A text search (`rg`, `grep`, `git grep`, `Select-String`). [targets] empty = the working directory [cwd]. */
    data class Search(
        val patterns: List<String>,
        val ignoreCase: Boolean,
        val word: Boolean,
        val fixed: Boolean,
        val targets: List<String>,
        val cwd: String,
        val filter: Filter,
    ) : ShellIntent

    /** A listing of files by name (`find -name`, `rg --files`, `Get-ChildItem -Filter`). */
    data class FileList(val names: List<String>, val roots: List<String>, val cwd: String) : ShellIntent

    /** A read of one file, whole or in part (`cat`, `head`, `tail`, `sed -n`, `Get-Content`). */
    data class Read(
        val path: String,
        val head: Int? = null,
        val tail: Int? = null,
        val from: Int? = null,
        val to: Int? = null,
    ) : ShellIntent {
        /** The lines this read returns from a file of [total] lines. */
        fun lines(total: Int): Int = when {
            head != null -> minOf(head, total)
            tail != null -> minOf(tail, total)
            from != null -> maxOf(0, minOf(to ?: total, total) - from + 1)
            else -> total
        }
    }

    /** What a search was restricted to by glob, type or include: nothing, source files, or other kinds of files. */
    enum class Filter { NONE, SOURCE, OTHER }
}
