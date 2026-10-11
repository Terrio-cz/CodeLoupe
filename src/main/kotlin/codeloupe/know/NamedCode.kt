package codeloupe.know

/**
 * File names (`Foo.kt`) and class names (`FooService`) a line of text uses without a directory: what may be anchored once the index says
 * where they are. A class name needs two words in CamelCase, a plain capitalised word is prose; a name inside a cited path is not one.
 */
data class NamedCode(val files: List<String>, val classes: List<String>) {
    val isEmpty: Boolean get() = files.isEmpty() && classes.isEmpty()

    companion object {
        /** More names than this in one line are not about one place. */
        const val MAX_PER_KIND = 6

        private val FILE = Regex("""(?<![\w@:/.\\-])([\w-]+(?:\.[\w-]+)*\.(?:${CitedPaths.EXTENSIONS})(?!\w))""")
        private val CLASS = Regex("""(?<![\w.#/@:-])([A-Z][A-Za-z0-9]*)(?!\w)(?!\.(?:${CitedPaths.EXTENSIONS})(?!\w))""")
        private val HUMP = Regex("[a-z0-9][A-Z]")

        fun of(text: String): NamedCode = NamedCode(
            FILE.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_PER_KIND).toList(),
            CLASS.findAll(text).map { it.groupValues[1] }.filter { HUMP.containsMatchIn(it) }.distinct().take(MAX_PER_KIND).toList(),
        )
    }
}
