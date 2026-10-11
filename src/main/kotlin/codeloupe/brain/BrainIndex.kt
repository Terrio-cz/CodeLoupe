package codeloupe.brain

import codeloupe.index.SearchWords

/** One workspace brain, searchable: its entries and task finding lines with a BM25 index over their stems. Immutable; a change makes a new one. */
class BrainIndex(val snapshot: BrainSnapshot, val builtAt: Long = System.currentTimeMillis()) {
    val items: List<IndexedEntry> = (snapshot.entries + snapshot.findings).map { IndexedEntry(it, it.id?.let(snapshot.feedback::get)) }
    private val bm25 = Bm25(items.map { it.words })

    val info: BrainWorkspaceInfo get() = snapshot.workspace

    /** BM25 score per item index for the question's terms. A file path counts by its file name, as the engine does. */
    fun textScores(terms: List<String>): Map<Int, Double> = bm25.scores(stems(terms))

    fun item(id: String): IndexedEntry? = items.firstOrNull { it.entry.id == id }

    companion object {
        private val EXTENSION = Regex("\\.(?:kts?|java|mjs|js|ts|ps1|sql|ya?ml|json|sh|md)(?::\\d+(?:-\\d+)?)?$", RegexOption.IGNORE_CASE)

        fun stems(terms: List<String>): List<String> = terms.flatMap { raw ->
            val term = raw.trim()
            val name = if (EXTENSION.containsMatchIn(term)) term.replace('\\', '/').substringAfterLast('/').replace(EXTENSION, "") else term
            SearchWords.terms(name).keys
        }.distinct()
    }
}
