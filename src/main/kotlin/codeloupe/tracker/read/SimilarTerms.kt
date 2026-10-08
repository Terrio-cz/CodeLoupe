package codeloupe.tracker.read

/** The distinctive words of a draft issue: what `similar` searches the mirror for. */
object SimilarTerms {
    private const val MAX_TERMS = 16
    private const val MIN_LENGTH = 3

    // Words that open a task title or glue a sentence, in English and Czech: they match everything and say nothing.
    private val STOP = setOf(
        "the", "and", "for", "with", "from", "into", "that", "this", "these", "those", "are", "was", "were", "not", "but", "can", "will", "should", "must",
        "has", "have", "had", "its", "their", "when", "then", "than", "also", "only", "each", "every", "any", "all", "one", "per", "via", "out", "off",
        "add", "use", "using", "used", "make", "new", "get", "set", "fix", "support", "allow", "need", "needs", "instead", "after", "before", "over", "under",
        "issue", "task", "tasks", "context", "scope", "criteria", "acceptance", "verification", "todo",
        "pro", "nebo", "jako", "kdy", "kdyz", "aby", "ale", "bez", "mezi", "pri", "pod", "nad", "jen", "tak", "tento", "tato", "toto", "jsou", "byt", "ktery",
    )

    /** Summary words first (they weigh most), then description words by how often they occur; at most 16 distinct. */
    fun of(summary: String, description: String): List<String> {
        val ordered = LinkedHashSet<String>()
        words(summary).forEach { ordered += it }
        words(description).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { ordered += it.key }
        return ordered.take(MAX_TERMS)
    }

    /** The searchable words of [text], lower case, in order; what `similar` also uses to see which terms an issue shares. */
    fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= MIN_LENGTH && it !in STOP && !it.all(Char::isDigit) }
}
