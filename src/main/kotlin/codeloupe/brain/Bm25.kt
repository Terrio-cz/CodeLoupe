package codeloupe.brain

import kotlin.math.ln

/**
 * Okapi BM25 over a fixed set of documents given as stem lists. A query stem matches a document stem that equals it, or (from
 * [MIN_PREFIX] characters) starts with it, `config` finding `configuration` at a lower weight.
 */
class Bm25(documents: List<List<String>>, private val k1: Double = 1.2, private val b: Double = 0.75) {
    private class Posting(val doc: Int, val tf: Int)

    private val postings = HashMap<String, MutableList<Posting>>()
    private val lengths = IntArray(documents.size)
    private val count = documents.size
    private val averageLength: Double

    init {
        documents.forEachIndexed { doc, words ->
            lengths[doc] = words.size
            words.groupingBy { it }.eachCount().forEach { (word, tf) -> postings.getOrPut(word) { ArrayList() } += Posting(doc, tf) }
        }
        averageLength = if (count == 0) 1.0 else maxOf(1.0, lengths.average())
    }

    /** Score per document for [query] stems; documents that match nothing are absent. */
    fun scores(query: Collection<String>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        for (term in query.toSet()) {
            for ((word, list) in matching(term)) {
                val weight = if (word == term) 1.0 else PREFIX_WEIGHT
                val idf = ln(1.0 + (count - list.size + 0.5) / (list.size + 0.5))
                for (p in list) {
                    val norm = p.tf * (k1 + 1) / (p.tf + k1 * (1 - b + b * lengths[p.doc] / averageLength))
                    out.merge(p.doc, weight * idf * norm, Double::plus)
                }
            }
        }
        return out
    }

    private fun matching(term: String): List<Pair<String, List<Posting>>> {
        val exact = postings[term]?.let { listOf(term to it) }.orEmpty()
        if (term.length < MIN_PREFIX) return exact
        return exact + postings.entries.filter { it.key != term && it.key.startsWith(term) }.map { it.key to it.value }
    }

    companion object {
        const val MIN_PREFIX = 5
        private const val PREFIX_WEIGHT = 0.6
    }
}
