package codeloupe.know

import codeloupe.index.SearchWords

/** The FTS5 expression for a question: its stems joined by OR, a longer stem also as a prefix (`config` finds `configuration`). */
object FtsMatch {
    private const val MIN_PREFIX = 5

    fun of(question: String): String? {
        val terms = SearchWords.terms(question).keys
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { term -> if (term.length >= MIN_PREFIX) "(\"$term\" OR \"$term\"*)" else "\"$term\"" }
    }
}
