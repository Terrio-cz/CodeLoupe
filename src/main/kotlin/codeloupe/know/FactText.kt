package codeloupe.know

import codeloupe.index.SearchWords

/** What the store makes of a fact's text: one clean line, and the stems search and dedupe compare. */
object FactText {
    const val MAX_LENGTH = 2000
    private val COMMENT = Regex("<!--.*?-->")

    /** One line: HTML comments dropped (a note's timestamp), runs of white space folded. */
    fun clean(raw: String): String {
        val text = raw.replace(COMMENT, " ").replace(Regex("\\s+"), " ").trim()
        require(text.isNotEmpty()) { "a fact needs text" }
        require(text.length <= MAX_LENGTH) { "a fact is one line of at most $MAX_LENGTH characters; this has ${text.length}" }
        return text
    }

    // The search words drop one-digit words, but "6 GB" and "8 GB" are two facts.
    private val LONE_DIGIT = Regex("(?<![\\w.])\\d(?![\\w.])")

    /** Stems of the text, repeats kept: what the full-text index holds. */
    fun words(text: String): List<String> = SearchWords.split(text) + LONE_DIGIT.findAll(text).map { it.value }
}
