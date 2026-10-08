package codeloupe.index

/**
 * The words one declaration is indexed under: of its name, of its container and file name, of its signature, of its
 * documentation comment and of the directories. The index stores them without their text (contentless FTS5), so a query
 * derives the same columns again from the facts and the file when it scores a hit.
 */
class SearchColumns(val name: List<String>, val ctx: List<String>, val sig: List<String>, val doc: List<String>, val dirs: List<String>) {
    fun all(): List<List<String>> = listOf(name, ctx, sig, doc, dirs)

    companion object {
        private const val MAX_DOC_WORDS = 60
        private const val MAX_SIG_WORDS = 12
        private val DOC = Regex("""/\*\*(.*?)\*/""", RegexOption.DOT_MATCHES_ALL)

        // Leading stars, @param/@return tags and the markup of links add words nobody searches for.
        private val TAG = Regex("""(?m)^\s*\*+|@(?:param|return|throws|see|since|author|property|constructor)\b|[{}\[\]`*]""")

        private val TYPE_KINDS = setOf("class", "interface", "object", "enum", "annotation", "typealias")

        fun of(path: String, kind: String, name: String, container: String, sig: String, doc: List<String>): SearchColumns =
            of(SearchFileWords.of(path), kind, name, container, sig, doc)

        fun of(file: SearchFileWords, kind: String, name: String, container: String, sig: String, doc: List<String>): SearchColumns {
            val words = SearchWords.split(name)
            return SearchColumns(
                name = words,
                ctx = SearchWords.split(container) + file.stem,
                sig = (SearchWords.signature(sig) - words.toSet()).take(MAX_SIG_WORDS),
                doc = doc,
                // Directory words on every member would repeat them dozens of times; the type that holds the members carries them.
                dirs = if (container.isEmpty() || kind in TYPE_KINDS) file.dirs else emptyList(),
            )
        }

        /** Words of the documentation comment between line [startLine] and the declaration at [declLine] (1-based) of a file's [lines]. */
        fun docWords(lines: List<String>, startLine: Int, declLine: Int): List<String> {
            if (startLine >= declLine) return emptyList()
            val text = lines.subList((startLine - 1).coerceAtLeast(0), (declLine - 1).coerceAtMost(lines.size)).joinToString("\n")
            val comment = DOC.find(text)?.groupValues?.get(1) ?: return emptyList()
            return SearchWords.split(TAG.replace(comment, " ")).take(MAX_DOC_WORDS)
        }
    }
}
