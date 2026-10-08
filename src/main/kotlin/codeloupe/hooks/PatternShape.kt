package codeloupe.hooks

/** What a search pattern looks for, and so which CodeLoupe tool answers it. */
sealed interface PatternShape {
    /** A declaration (`class Foo`, `fun bar`): `find`. [kind] is the CodeLoupe kind, null when the keyword fits several. */
    data class Declaration(val name: String, val kind: String?) : PatternShape

    /** A name used in code (`OrderService`, `parseConfig(`): `usages`. */
    data class Name(val name: String) : PatternShape

    /** Anything else, a string literal or a regular expression: `grep`. */
    data class Text(val pattern: String, val regex: Boolean) : PatternShape

    companion object {
        private const val IDENT = "[A-Za-z_][A-Za-z0-9_]*"
        private val BOUNDARY = Regex("""\\b|\\<|\\>|^\^|\$$""")
        private const val MODIFIERS = "private|public|internal|protected|override|open|abstract|data|sealed|suspend|inline|enum|annotation|value|const|lateinit"
        private val DECLARATION = Regex("""^(?:\^|\\s[*+]|\\b|\s|$MODIFIERS)*(class|interface|object|fun|val|var|typealias)(?:\\s[*+]|\s)+($IDENT)(?:[^|\w][^|]*)?$""")
        private val NAME = Regex("""^$IDENT(?:\.$IDENT)*$""")
        private val CALL = Regex("""^($IDENT(?:\.$IDENT)*)(?:\\\(|\(|\\\.|\\b)?$""")
        private val META = Regex("""[\\^$.*+?()\[\]{}|]""")
        private val KINDS = mapOf("interface" to "interface", "object" to "object", "fun" to "fun", "val" to "property", "var" to "property", "typealias" to "typealias")

        /** [pattern] as the regular expression or literal text a shell search was given; [fixed] when it was a literal. */
        fun of(pattern: String, fixed: Boolean): PatternShape {
            val text = pattern.trim()
            if (fixed) return literal(text)
            DECLARATION.find(text)?.let { return Declaration(it.groupValues[2], KINDS[it.groupValues[1]]) }
            val bare = text.replace(BOUNDARY, "")
            CALL.matchEntire(bare)?.let { if (symbolLike(it.groupValues[1])) return Name(it.groupValues[1]) }
            return Text(text, regex = META.containsMatchIn(text))
        }

        private fun literal(text: String): PatternShape {
            val bare = text.removeSuffix("(")
            return if (NAME.matches(bare) && symbolLike(bare)) Name(bare) else Text(text, regex = false)
        }

        // `OrderService`, `parseConfig`, `MAX_SIZE`: a word that is a name rather than English; `error`, `timeout` or `order_id` is text.
        private fun symbolLike(name: String): Boolean = name.length > 2 && name.drop(1).let { rest -> rest.any { it.isUpperCase() } || name.first().isUpperCase() }
    }
}
