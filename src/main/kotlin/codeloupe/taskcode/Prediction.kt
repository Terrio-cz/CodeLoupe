package codeloupe.taskcode

/**
 * One predicted touch of an open task: [target] is a path (with `:lines` for a declaration) or a module, [mark] how
 * sure the prediction is, [evidence] where the issue said so.
 */
data class Prediction(val mark: Char, val target: String, val path: String?, val detail: String, val evidence: List<String>) {
    companion object {
        /** The path exists or the symbol resolves to one declaration. */
        const val SURE = '='

        /** One match, but by a weaker clue: a word in prose, a string found in one file. */
        const val LIKELY = '~'

        /** Several candidates, or a module. */
        const val GUESS = '?'

        /** A path the issue names that the index does not have: probably to be created. */
        const val NEW = '+'

        val ORDER = listOf(SURE, LIKELY, GUESS, NEW)
    }
}
