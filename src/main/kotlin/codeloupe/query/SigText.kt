package codeloupe.query

/** A signature as a line of an answer: without the KDoc and comments inside its parameter list, on one line. */
internal object SigText {
    private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val SPACES = Regex("""\s+""")

    fun plain(sig: String): String = if ('/' !in sig && '\n' !in sig) sig else COMMENT.replace(sig, "").replace(SPACES, " ").replace("( ", "(").replace(" )", ")").trim()
}
