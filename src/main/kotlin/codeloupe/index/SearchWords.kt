package codeloupe.index

/**
 * Words of an identifier, a signature or a comment as the search index holds them: lowercase, split at camelCase and
 * snake_case boundaries, plurals and verb endings folded (`limits`, `limited` and `limiting` are all `limit`), and
 * function words dropped. The index and the query use the same rules, so a word matches exactly when its stems are equal.
 */
object SearchWords {
    private val STOP = setOf(
        "the", "an", "of", "in", "on", "to", "for", "and", "or", "is", "are", "was", "were", "be", "been", "how", "what", "where",
        "which", "who", "when", "why", "with", "from", "by", "at", "as", "it", "its", "this", "that", "these", "those", "do", "does",
        "did", "can", "should", "will", "not", "if", "then", "else", "we", "you", "all", "any", "into", "via", "code", "find",
        "function", "method", "used", "uses", "use",
    )

    // Modifiers and keywords every signature repeats: they say nothing about what a declaration is for.
    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "interface", "object", "private", "public", "internal", "protected", "override", "suspend",
        "abstract", "open", "final", "data", "sealed", "enum", "const", "lateinit", "inline", "operator", "static", "void", "return",
        "annotation", "companion", "typealias", "vararg", "tailrec", "infix", "extends", "implements", "throws", "new", "null",
        "string", "int", "long", "boolean", "unit",
    )

    private val SIBILANT_ES = listOf("sses", "xes", "zes", "ches", "shes")

    /** The stems of every word in [text] but function words, in order, repeats kept. */
    fun split(text: String): List<String> = raw(text).filter { it !in STOP }.map(::stem)

    /** As [split] without the keywords of a signature. */
    fun signature(text: String): List<String> = raw(text).filter { it !in STOP && it !in KEYWORDS }.map(::stem)

    /** The distinct stems of a query, each with the word the user wrote, in order. */
    fun terms(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (word in raw(query)) if (word !in STOP) out.putIfAbsent(stem(word), word)
        return out
    }

    /** Lowercase words of [text], cut at non-alphanumerics, camelCase humps and digit edges. */
    private fun raw(text: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()

        fun flush() {
            if (word.length >= 2) words += word.toString().lowercase()
            word.setLength(0)
        }
        for (i in text.indices) {
            val c = text[i]
            if (!c.isLetterOrDigit()) {
                flush()
                continue
            }
            if (word.isNotEmpty()) {
                val prev = text[i - 1]
                val hump = c.isUpperCase() && (prev.isLowerCase() || prev.isDigit() ||
                    (prev.isUpperCase() && i + 1 < text.length && text[i + 1].isLowerCase()))
                if (hump || c.isDigit() != prev.isDigit()) flush()
            }
            word.append(c)
        }
        flush()
        return words
    }

    private fun stem(word: String): String {
        var w = word
        if (w.length > 4 && w.endsWith("ies")) {
            w = w.dropLast(3) + "y"
        } else if (w.length > 4 && SIBILANT_ES.any(w::endsWith)) {
            w = w.dropLast(2)
        } else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            w = w.dropLast(1)
        }
        if (w.length > 5 && w.endsWith("ing")) w = w.dropLast(3) else if (w.length > 4 && w.endsWith("ed")) w = w.dropLast(2)
        if (w.length > 4 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }
}
