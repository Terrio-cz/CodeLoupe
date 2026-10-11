package codeloupe.brain

import kotlin.math.roundToInt

/**
 * Orders the entries of a brain for a question. Code proximity comes first: an entry tied to the files or symbols asked about (by an anchor, or
 * by a file or symbol its text names) outranks one tied to a file that calls them, which outranks one in the same module, which outranks one from a task that
 * changed the same code; text (BM25 over the terms) only orders entries inside a tier and carries those with no code link at all.
 * Feedback adds a little for an entry that predicted a finding in a later task and never takes anything away. Whole entries, never cut.
 */
class BrainRanker(private val index: BrainIndex) {
    private class Signal(val code: Int, val tiers: List<String>, val text: Int, val feedback: Int) {
        // The code link decides the order (hundreds); text and feedback only order entries with the same link.
        val score: Int get() = (code + MULTI * maxOf(0, tiers.size - 1)) * 100 + text + feedback
        val anchored: Boolean get() = "file" in tiers || "symbol" in tiers
        fun why(): List<String> = tiers + listOfNotNull("text".takeIf { text > 0 }, "feedback".takeIf { feedback > 0 })
    }

    fun rank(request: BrainRankRequest, code: CodeContext): Map<String, BrainRankSection> {
        val text = if (request.terms.isEmpty()) emptyMap() else index.textScores(request.terms)
        val top = text.values.maxOrNull() ?: 0.0
        val signals = index.items.mapIndexed { i, item -> signal(item, code, if (top > 0) (text[i] ?: 0.0) / top else 0.0) }
        return request.sections.associate { section -> section.name to select(section, request, signals) }
    }

    private fun select(section: BrainSection, request: BrainRankRequest, signals: List<Signal>): BrainRankSection {
        val picked = index.items.indices.filter { i ->
            val e = index.items[i].entry
            val s = signals[i]
            (s.code > 0 || s.text > 0) && (!section.anchoredOnly || s.anchored) && wanted(e, section, request)
        }.sortedWith(compareByDescending<Int> { signals[it].score }.thenByDescending { index.items[it].entry.at.orEmpty() }.thenBy { index.items[it].entry.where })
        val limit = section.limit.coerceIn(1, MAX_LIMIT)
        return BrainRankSection(picked.take(limit).map { i -> index.items[i].entry.copy(score = signals[i].score, why = signals[i].why()) }, (picked.size - limit).coerceAtLeast(0))
    }

    private fun wanted(e: BrainEntry, section: BrainSection, request: BrainRankRequest): Boolean {
        if (e.superseded && !request.all) return false
        if (request.task != null && e.task == request.task) return false
        if (section.kinds.isNotEmpty() && e.kind !in section.kinds) return false
        if (e.isTaskLine) return section.tasks
        if (section.levels.isNotEmpty() && e.level !in section.levels) return false
        return section.scopes.isEmpty() || section.scopes.any(e::under)
    }

    private fun signal(item: IndexedEntry, code: CodeContext, text: Double): Signal {
        val tiers = LinkedHashMap<String, Int>()
        if (!code.isEmpty) {
            if (item.pathKeys.any { key -> code.paths.any(key::matches) }) tiers["file"] = FILE
            if (item.symbolAnchors.any { it in code.symbolKeys }) tiers["symbol"] = SYMBOL
            else if (item.namedSymbols.any { it in code.symbolKeys }) tiers["symbol"] = NAMED_SYMBOL
            if (item.entry.task != null && item.entry.task in code.taskIds) tiers["task"] = TASK
            if (code.callerPaths.isNotEmpty() && item.pathKeys.any { key -> code.callerPaths.any(key::matches) }) tiers["caller"] = CALLER
            if ((item.moduleAnchors + item.moduleHints).any { it in code.modules }) tiers["module"] = MODULE
        }
        val feedback = item.feedback?.takeIf { it.hit > 0 }?.let { minOf(FEEDBACK_CAP, it.hit * FEEDBACK_STEP) } ?: 0
        return Signal(tiers.values.maxOrNull() ?: 0, tiers.keys.toList(), (text * TEXT_MAX).roundToInt(), feedback)
    }

    companion object {
        const val FILE = 100
        const val SYMBOL = 100
        const val NAMED_SYMBOL = 85
        const val TASK = 30
        const val CALLER = 60
        const val MODULE = 45
        const val TEXT_MAX = 20
        const val FEEDBACK_STEP = 4
        const val FEEDBACK_CAP = 12
        private const val MULTI = 5
        private const val MAX_LIMIT = 500
    }
}
