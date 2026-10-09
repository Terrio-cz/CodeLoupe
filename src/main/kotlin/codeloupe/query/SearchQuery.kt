package codeloupe.query

import codeloupe.index.SearchColumns
import codeloupe.index.SearchWords
import kotlin.math.ln
import kotlin.math.min

/**
 * `find mode=search`: declarations ranked for the words of a question ("token limit", "where are retries scheduled").
 * FTS5 picks the candidates of the base and of the overlay; they are scored together here, with the word rarity of the
 * base, so a hit in an edited file ranks like one in an untouched file.
 */
internal object SearchQuery {
    private const val CANDIDATES = 60
    private const val POPULARITY_POOL = 30
    private const val K1 = 1.2
    private const val B = 0.5
    private const val MIN_PREFIX = 4
    private const val MAX_EXTENSION = 4

    // Weight and typical length (words) of the indexed columns, in the order of SearchColumns.all(): name, ctx, sig, doc, dirs.
    private val WEIGHT = doubleArrayOf(3.0, 1.0, 0.7, 1.3, 0.5)
    private val AVG_LENGTH = doubleArrayOf(3.0, 4.0, 5.0, 20.0, 4.0)

    private val KIND_PRIOR = mapOf(
        "class" to 1.15, "interface" to 1.15, "object" to 1.1, "enum" to 1.1, "annotation" to 1.05, "typealias" to 1.0,
        "fun" to 1.0, "property" to 0.85, "enum_entry" to 0.8,
    )

    fun run(view: View, args: FindQuery.Args): String {
        val terms = SearchWords.terms(args.q.orEmpty())
        if (terms.isEmpty()) return "no searchable words in \"${args.q}\""
        val match = terms.keys.joinToString(" OR ", transform = ::expression)
        val filter = DeclFilter.of(args.kind, args.module, args.test, locals = false)

        val rows = ArrayList<DeclRow>()
        val ranks = HashMap<Pair<String, Long>, Double>()
        for ((hits, src) in listOf(view.baseSearch(match, CANDIDATES, filter) to "base", view.overlaySearch(match, CANDIDATES, filter) to "ov")) {
            if (hits.isEmpty()) continue
            val where = "d.id IN (${hits.joinToString(",") { it.id.toString() }})"
            val found = if (src == "ov") view.overlayDecls(where) else view.baseDecls(where)
            hits.forEach { ranks[src to it.id] = it.rank }
            rows += found
        }
        if (rows.isEmpty()) return "no declaration matches the words of \"${args.q}\""

        val total = view.baseSearchCounts(null).coerceAtLeast(1)
        val idf = terms.keys.associateWith { term ->
            val df = view.baseSearchCounts("\"$term\"")
            ln(1.0 + (total - df + 0.5) / (df + 0.5))
        }
        val lines = HashMap<String, List<String>>()
        val testsWanted = args.test == true
        val scored = rows.map { row ->
            val doc = SearchColumns.docWords(lines.getOrPut(row.path) { view.file(row.path)?.content?.lines().orEmpty() }, row.startLine, row.declLine)
            score(row, SearchColumns.of(row.path, row.kind, row.name, row.container, row.sig, doc), terms.keys, idf, testsWanted)
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { ranks[it.row.src to it.row.id] })

        val limit = args.limit ?: 10
        val ranked = scored.take(POPULARITY_POOL).map { s ->
            // A name that many places refer to is more likely what is asked about; a gentle boost, not a ranking by itself.
            val refs = view.refCount(s.row.name, 200)
            s.copy(score = s.score * (1.0 + 0.06 * ln(1.0 + min(refs, 200))))
        }.sortedByDescending { it.score } + scored.drop(POPULARITY_POOL)

        return ranked.take(limit).joinToString("\n") { s ->
            Format.head(s.row) + "  (" + s.matched.joinToString(", ") { terms.getValue(it) } + ")"
        }
    }

    private fun score(row: DeclRow, columns: SearchColumns, terms: Set<String>, idf: Map<String, Double>, testsWanted: Boolean): Scored {
        var score = 0.0
        val matched = ArrayList<String>()
        for (term in terms) {
            var weighted = 0.0
            columns.all().forEachIndexed { i, words ->
                val tf = words.sumOf { strength(term, it) }
                if (tf > 0) weighted += WEIGHT[i] * tf * (K1 + 1) / (tf + K1 * (1 - B + B * words.size / AVG_LENGTH[i]))
            }
            if (weighted > 0) {
                matched += term
                score += idf.getValue(term) * weighted
            }
        }
        if (matched.size == terms.size && terms.size > 1) score *= 1.25
        score *= KIND_PRIOR[row.kind] ?: 1.0
        if (!testsWanted && row.sourceSet.contains("test")) score *= 0.85
        return Scored(row, score, matched)
    }

    /**
     * The words that may stand for [term] in the index: itself, a word that extends it (`detect` for `detector`) and a
     * word it extends (`config` for `configuration`, `decl` for `declaration`), the shortest of four letters.
     */
    private fun expression(term: String): String {
        val parts = arrayListOf("\"$term\"")
        if (term.length >= MIN_PREFIX) parts += "\"$term\"*"
        for (k in MIN_PREFIX until term.length) parts += "\"${term.take(k)}\""
        return parts.joinToString(" OR ", "(", ")")
    }

    /** How well the indexed [word] stands for [term]: 1 when equal, less when one is the beginning of the other. */
    private fun strength(term: String, word: String): Double = when {
        word == term -> 1.0
        term.length >= MIN_PREFIX && word.startsWith(term) && word.length - term.length <= MAX_EXTENSION -> 0.7
        word.length >= MIN_PREFIX && term.startsWith(word) -> 0.6
        else -> 0.0
    }

    private data class Scored(val row: DeclRow, val score: Double, val matched: List<String>)
}
