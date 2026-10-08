package codeloupe.taskcode

/** One declaration a commit changed: the marks of `changes` (`+ ~ ^ -`), with the lines of the version a reader looks at. */
data class CommitDecl(val path: String, val mark: Char, val kind: String, val container: String, val name: String, val sig: String, val startLine: Int, val endLine: Int) {
    val qualifiedName: String get() = if (container.isEmpty()) name else "$container.$name"
}
