package codeloupe.jobs

/**
 * POSIX shell words. A job runs its argv without a shell; the policy hook still sees one Bash command line, quoted so
 * that Bash would split it back into exactly that argv — what the hook judges is what runs.
 */
object CommandLine {
    fun join(argv: List<String>): String = argv.joinToString(" ", transform = ::quote)

    /** `K='v' … cmd args`: how Bash spells the same command with [env] set. */
    fun withEnv(env: Map<String, String>, argv: List<String>): String =
        (env.map { (k, v) -> "$k=${quote(v)}" } + argv.map(::quote)).joinToString(" ")

    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in SAFE }) word else "'" + word.replace("'", "'\\''") + "'"

    /** Splits [line] into words: whitespace separates, '…' is literal, "…" and \ escape. No expansion of any kind. */
    fun split(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).takeIf { it >= 0 } ?: throw IllegalArgumentException("unclosed ' in: $line")
                    word.append(line, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\\$`") i++
                        word.append(line[i++])
                    }
                    if (i >= line.length) throw IllegalArgumentException("unclosed \" in: $line")
                    inWord = true
                }
                c == '\\' && i + 1 < line.length -> {
                    word.append(line[++i])
                    inWord = true
                }
                c.isWhitespace() -> {
                    if (inWord) words += word.toString()
                    word.clear()
                    inWord = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words
    }

    private const val SAFE = "@%+=:,./_-"
}
