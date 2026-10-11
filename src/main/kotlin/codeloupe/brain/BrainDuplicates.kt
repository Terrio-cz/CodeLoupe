package codeloupe.brain

import codeloupe.know.Dedupe
import codeloupe.know.FactText
import kotlin.math.round

/**
 * Pairs of workspace entries that are the same lesson in other words: stems at least [Dedupe.THRESHOLD] alike with the same numbers (TER-167 and
 * TER-168 are two entries however alike the rest), as the knowledge store judges its own duplicates. An entry that already supersedes the other is
 * settled and not proposed again.
 */
object BrainDuplicates {
    private class Doc(val entry: BrainEntry, val words: Set<String>)

    fun of(entries: List<BrainEntry>): List<HygieneReport.Duplicate> {
        val docs = entries.filter { it.id != null && !it.superseded && it.level == "workspace" }.map { Doc(it, FactText.words(it.text).toSet()) }
        val out = ArrayList<HygieneReport.Duplicate>()
        for (i in docs.indices) {
            for (j in i + 1 until docs.size) {
                val a = docs[i]
                val b = docs[j]
                if (!Dedupe.same(a.words, b.words)) continue
                if (a.entry.id in b.entry.supersedes || b.entry.id in a.entry.supersedes) continue
                out += HygieneReport.Duplicate(
                    a.entry.id.orEmpty(), b.entry.id.orEmpty(), round(Dedupe.similarity(a.words, b.words) * 100) / 100,
                    a.entry.text, b.entry.text, a.entry.where, b.entry.where,
                )
            }
        }
        return out
    }
}
