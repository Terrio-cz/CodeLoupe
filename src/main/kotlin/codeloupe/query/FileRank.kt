package codeloupe.query

/**
 * PageRank over the files of a repository: a file that many files reference ranks high, and the files it references
 * lend it rank in turn. With a [personalization] the walk keeps jumping back to those files, so their neighbourhood
 * ranks above the rest of the repository.
 */
internal object FileRank {
    private const val DAMPING = 0.85
    private const val ITERATIONS = 40
    private const val EPSILON = 1e-7

    /** Edge weights `from -> to -> weight`; every file in [nodes] gets a score, the scores add up to 1. */
    fun rank(nodes: Set<String>, edges: Map<String, Map<String, Double>>, personalization: Set<String> = emptySet()): Map<String, Double> {
        if (nodes.isEmpty()) return emptyMap()
        val ids = nodes.sorted().withIndex().associate { it.value to it.index }
        val n = ids.size
        val teleport = DoubleArray(n)
        val focus = personalization.filter { it in ids }
        if (focus.isEmpty()) teleport.fill(1.0 / n) else focus.forEach { teleport[ids.getValue(it)] = 1.0 / focus.size }
        val out = Array(n) { IntArray(0) }
        val weights = Array(n) { DoubleArray(0) }
        for ((from, targets) in edges) {
            val f = ids[from] ?: continue
            val kept = targets.filter { it.key in ids && it.key != from }
            val total = kept.values.sum()
            if (total <= 0) continue
            out[f] = kept.keys.map { ids.getValue(it) }.toIntArray()
            weights[f] = kept.values.map { it / total }.toDoubleArray()
        }
        var rank = teleport.copyOf()
        repeat(ITERATIONS) {
            val next = DoubleArray(n)
            var dangling = 0.0
            for (i in 0 until n) {
                if (out[i].isEmpty()) dangling += rank[i]
                else for (k in out[i].indices) next[out[i][k]] += DAMPING * rank[i] * weights[i][k]
            }
            var delta = 0.0
            for (i in 0 until n) {
                // Rank of files that reference nothing, and the jump, go back to the teleport set.
                next[i] += (DAMPING * dangling + (1 - DAMPING)) * teleport[i]
                delta += Math.abs(next[i] - rank[i])
            }
            rank = next
            if (delta < EPSILON) return ids.mapValues { rank[it.value] }
        }
        return ids.mapValues { rank[it.value] }
    }
}
