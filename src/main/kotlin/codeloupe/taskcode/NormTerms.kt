package codeloupe.taskcode

import codeloupe.index.ModulePath

/**
 * What a task is about, as weighted words: the module of each touched file (3, its parent folders 2), the pairs of adjacent words of its name (2) and its single words (1, or 2 for a one-word name).
 * Matched against prose at a word start, so `accounts` finds the sentence about `:accounts`.
 */
class NormTerms(val weights: Map<String, Int>, val fileNames: Set<String>) {
    companion object {
        fun of(paths: List<String>): NormTerms {
            val weights = HashMap<String, Int>()
            fun add(word: String, weight: Int, min: Int = MIN_WORD) {
                val w = word.lowercase()
                if (w.length >= min && w !in GENERIC) weights.merge(w, weight, ::maxOf)
            }
            val names = LinkedHashSet<String>()
            for (path in paths) {
                val module = ModulePath.of(path).module.split('/').filter { it.isNotEmpty() }
                module.forEach { add(it, 2, min = 3) }
                module.lastOrNull()?.let { add(it, 3, min = 3) }
                val file = path.substringAfterLast('/')
                names += file
                val parts = words(file.substringBeforeLast('.'))
                parts.forEach { add(it, if (parts.size == 1) 2 else 1) }
                parts.zipWithNext { a, b -> a + b }.forEach { add(it, 2) }
            }
            return NormTerms(weights, names)
        }

        /** `HttpOpenApi` -> Http, Open, Api; `local-stack` -> local, stack. */
        private fun words(text: String): List<String> = text.split(Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")).filter { it.isNotEmpty() }

        private const val MIN_WORD = 4
        private val GENERIC = setOf("main", "kotlin", "java", "test", "tests", "resources", "with", "from", "that", "this", "into", "when", "only", "each", "have", "send", "make", "also", "than", "then", "their", "which")
    }
}
