package codeloupe.compress

/** The lines of an output that an agent must never lose: failures and compiler errors. */
object ErrorLines {
    private val ERROR = Regex(
        """(?i)(\bFAILED\b|\bFAIL\b|^e: |^error[:\[]|\berror:|\bfatal:|^not ok \d|\bException\b.*:|^Caused by:|BUILD FAILED|\bpanic:|\bTraceback\b|^\s*✗|^\s*×)""",
    )

    fun isError(line: String): Boolean = ERROR.containsMatchIn(line) || JavacDiagnostic.isError(line)

    /** [isError] for the line at [index], which may also be an error of a javac in a language the summary does not know. */
    fun isErrorAt(lines: List<String>, index: Int): Boolean =
        isError(lines[index]) || JavacDiagnostic.classify(lines, index)?.severity == JavacDiagnostic.Severity.ERROR

    fun shorten(line: String, max: Int = 200): String = line.trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    private val WINDOWS_URI = Regex("""file:///(?=[A-Za-z]:)""")

    /** [text] with the working directory and `file:///` cut off, so an error line carries only what is specific to it. */
    fun relative(text: String, cwd: String): String {
        // `file:///C:/x` is the path `C:/x`, `file:///home/x` is `/home/x`: the root slash stays so the directory still matches.
        var out = text.replace('\\', '/').replace(WINDOWS_URI, "").replace("file:///", "/")
        val base = cwd.trimEnd('/')
        if (base.isNotEmpty()) out = out.replace("$base/", "")
        return out
    }
}
