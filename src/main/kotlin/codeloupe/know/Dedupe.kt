package codeloupe.know

/**
 * Two facts of one scope are the same fact when their stems are near-identical: Jaccard similarity of at least [THRESHOLD], and
 * the same numbers (TER-167 and TER-168 are two facts, however alike the rest).
 */
object Dedupe {
    const val THRESHOLD = 0.85

    class Candidate(val id: Long, val words: Set<String>)

    /** The most similar candidate that counts as the same fact; the older one when two are equally similar. */
    fun nearest(words: Set<String>, candidates: List<Candidate>): Candidate? =
        candidates.filter { same(words, it.words) }.maxWithOrNull(compareBy<Candidate> { similarity(words, it.words) }.thenByDescending { it.id })

    fun same(a: Set<String>, b: Set<String>): Boolean = a.isNotEmpty() && b.isNotEmpty() && numbers(a) == numbers(b) && similarity(a, b) >= THRESHOLD

    fun similarity(a: Set<String>, b: Set<String>): Double {
        val shared = a.count { it in b }
        val all = a.size + b.size - shared
        return if (all == 0) 0.0 else shared.toDouble() / all
    }

    private fun numbers(words: Set<String>) = words.filter { w -> w.any { it.isDigit() } }.toSet()
}
