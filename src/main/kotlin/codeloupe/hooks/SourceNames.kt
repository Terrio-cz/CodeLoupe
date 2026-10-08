package codeloupe.hooks

/** Which file names and globs are the source code CodeLoupe indexes (Kotlin and Java). */
object SourceNames {
    private val SOURCE = Regex("""(?i)\.(kt|kts|java)$""")
    private val MENTION = Regex("""(?i)(^|[.,{*/])(kt|kts|java|kotlin)([,}\s]|$)""")
    private val EXTENSION = Regex("""\.[A-Za-z0-9]+$""")

    fun isSource(path: String) = SOURCE.containsMatchIn(path)

    // True for a glob, a ripgrep type or a name that selects source files: `*.kt`, `*.{kt,java}`, `kotlin`, `*Service.java`.
    fun mentionsSource(text: String) = SOURCE.containsMatchIn(text) || MENTION.containsMatchIn(text.replace("\"", ""))

    /** A path that ends in an extension which is not source (`.md`, `.json`): a document or configuration. */
    fun isOther(path: String) = !isSource(path) && EXTENSION.containsMatchIn(path.substringAfterLast('/'))
}
