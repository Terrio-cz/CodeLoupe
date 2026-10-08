package codeloupe.compress

/** The lines of an output that an agent must never lose: failures and compiler errors. */
object ErrorLines {
    private val ERROR = Regex(
        """(?i)(\bFAILED\b|\bFAIL\b|^e: |^error[:\[]|\berror:|\bfatal:|^not ok \d|\bException\b.*:|^Caused by:|BUILD FAILED|\bpanic:|\bTraceback\b|^\s*✗|^\s*×)""",
    )

    fun isError(line: String): Boolean = ERROR.containsMatchIn(line)

    fun shorten(line: String, max: Int = 200): String = line.trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    /** [path] with the working directory and `file:///` cut off, so an error line carries only what is specific to it. */
    fun relative(text: String, cwd: String): String {
        var out = text.replace('\\', '/').replace("file:///", "")
        val base = cwd.trimEnd('/')
        if (base.isNotEmpty()) out = out.replace("$base/", "")
        return out
    }
}
