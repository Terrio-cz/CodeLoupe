package codeloupe.hooks

/**
 * One command of a shell line with its quotes removed. [piped] when it reads the output of the command before it,
 * [writes] when its output goes to a file or an input is fed from a here-document.
 */
data class SimpleCommand(val words: List<String>, val piped: Boolean = false, val writes: Boolean = false) {
    val program: String get() = words.firstOrNull().orEmpty()
}
