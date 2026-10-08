package codeloupe.metrics

/** A shell command reduced to what it runs: `cd X && git -C wt diff …` -> `git diff`, `node run/t.mjs api …` -> `node t.mjs api`. */
object CommandKey {
    private val CD = Regex("""^(cd|Set-Location)\s+("[^"]+"|'[^']+'|\S+)\s*(&&|;)\s*""")
    private val ASSIGN = Regex("""^[A-Z_][A-Z0-9_]*=("[^"]*"|\S*)\s+""")
    private val EXPORT = Regex("""^(export\s+\S+|S=\S+|P=\S+)\s*;\s*""")
    private val DIR_OPTION = Regex("""\s-C\s+("[^"]+"|'[^']+'|\S+)""")
    private val WORDS = Regex(""""[^"]*"|'[^']*'|\S+""")
    private val INTERPRETER = Regex("""^(node|python3?|bash|sh|powershell|pwsh)$""", RegexOption.IGNORE_CASE)
    private val NOT_A_WORD_START = Regex("""^[-"'$]""")
    private val PATHLIKE = Regex("""[\\/.=]""")
    private val LEADING_QUOTE = Regex("""^["']""")
    private val TRAILING_QUOTE = Regex("""["']$""")
    private val DIRECTORY_PREFIX = Regex("""^.*[\\/]""")
    private val EXE = Regex("""\.exe$""", RegexOption.IGNORE_CASE)

    fun of(command: String?): String {
        var c = command.orEmpty().trim()
        while (true) {
            val next = c.replaceFirst(CD, "").replaceFirst(ASSIGN, "").replaceFirst(EXPORT, "")
            if (next == c) break
            c = next
        }
        c = c.replaceFirst(DIR_OPTION, "")
        val w = WORDS.findAll(c).map { it.value }.toList()
        val prog = base(w.getOrNull(0))
        val parts = if (INTERPRETER.matches(prog)) listOf(prog, base(w.getOrNull(1)), word(w.getOrNull(2))) else listOf(prog, word(w.getOrNull(1)))
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun base(s: String?): String =
        s.orEmpty().replaceFirst(LEADING_QUOTE, "").replaceFirst(TRAILING_QUOTE, "").replaceFirst(DIRECTORY_PREFIX, "").replaceFirst(EXE, "")

    private fun word(s: String?): String = if (!s.isNullOrEmpty() && !NOT_A_WORD_START.containsMatchIn(s) && !PATHLIKE.containsMatchIn(s)) s else ""
}
