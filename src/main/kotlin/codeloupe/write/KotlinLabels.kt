package codeloupe.write

/**
 * Kotlin labels that carry a function's name: `this@f` and `return@f` inside the body of `f`, `return@f` in a lambda passed to a call of `f`.
 * They are not references the index records, so a rename finds them in the text, in the two places they can stand.
 */
internal object KotlinLabels {
    /** The edits renaming the label `@old` inside `[start, end)` of [text]. */
    fun within(text: String, start: Int, end: Int, old: String, new: String): List<TextEdit> {
        val label = Regex("""(?<=\b(?:this|return|break|continue|super)@)${Regex.escape(old)}(?![\w$])""")
        return label.findAll(text.substring(start, end.coerceAtMost(text.length))).map { TextEdit(start + it.range.first, start + it.range.last + 1, new) }.toList()
    }

    /** The edits renaming the labels of the lambda that follows a call to [old] written at [site] (`old(args) { … return@old … }`). */
    fun afterCall(text: String, site: Int, old: String, new: String): List<TextEdit> {
        var i = site + old.length
        i = skipSpaces(text, i)
        if (text.startsWith("(", i)) i = skipSpaces(text, closing(text, i, '(', ')') + 1)
        if (i >= text.length || text[i] != '{') return emptyList()
        val close = closing(text, i, '{', '}')
        return within(text, i, close, old, new)
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    // The offset of the bracket that closes the one at [open]; the end of the text when it is not closed. Strings and comments are not told apart.
    private fun closing(text: String, open: Int, left: Char, right: Char): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                left -> depth++
                right -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }
}
