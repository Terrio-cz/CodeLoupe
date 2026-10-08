package codeloupe.index

/** What every declaration of one file shares in the search index: the words of the file name and of its directories. */
class SearchFileWords private constructor(val stem: List<String>, val dirs: List<String>) {
    companion object {
        private val NOISE_DIRS = setOf("src", "main", "test", "kotlin", "java", "com", "org", "io", "net", "cz", "resources", "")

        fun of(path: String): SearchFileWords {
            val parts = path.split('/')
            return SearchFileWords(
                SearchWords.split(parts.last().substringBeforeLast('.')),
                parts.dropLast(1).filter { it !in NOISE_DIRS }.flatMap(SearchWords::split),
            )
        }
    }
}
