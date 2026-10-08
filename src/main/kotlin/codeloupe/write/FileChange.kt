package codeloupe.write

/**
 * One file of a write. [path] is relative to the worktree, with `/`. [original] is the file as it was read (null: it does not exist
 * yet and is created); [text] is what it becomes. [movedTo] names a new path the text goes to instead, the file at [path] being removed.
 */
internal class FileChange(val path: String, val original: SourceText?, val text: String, val movedTo: String? = null) {
    val target: String get() = movedTo ?: path
}
