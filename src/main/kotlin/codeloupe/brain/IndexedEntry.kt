package codeloupe.brain

import codeloupe.index.SearchWords

/** An entry with what ranking compares: its files as keys, its symbol and module anchors, and its stems for BM25. */
class IndexedEntry(val entry: BrainEntry, val feedback: BrainFeedback?) {
    val pathKeys: List<BrainPathKey> = (entry.files + entry.anchors.filter { it.startsWith(FILE) }.map { it.removePrefix(FILE) }).mapNotNull(BrainPathKey::of)

    /** `@sym:` anchors without a signature, lowercase. */
    val symbolAnchors: List<String> = entry.anchors.filter { it.startsWith(SYM) }.map { it.removePrefix(SYM).substringBefore('(').lowercase() }

    /** Symbols the text names (`Foo.bar`, `Foo`), lowercase: less sure than an anchor. */
    val namedSymbols: List<String> = entry.symbols.map { it.substringBefore('(').lowercase() }

    val moduleAnchors: Set<String> = entry.anchors.filter { it.startsWith(MOD) }.map { it.removePrefix(MOD).lowercase() }.toSet()
    val moduleHints: Set<String> = pathKeys.flatMap { it.modulePrefixes() }.toSet()

    /**
     * Stems of everything the entry says and points at: text, files, symbols, anchors, topic and task. Computed for the BM25 index only, not
     * kept: a list of its own strings per entry was the largest part of a brain index (CL-238).
     */
    fun words(): List<String> = SearchWords.split(
        listOf(entry.text, entry.files.joinToString(" "), entry.symbols.joinToString(" "), entry.anchors.joinToString(" ") { it.substringAfter(':') }, entry.topic, entry.task.orEmpty()).joinToString(" "),
    )

    companion object {
        const val SYM = "@sym:"
        const val FILE = "@file:"
        const val MOD = "@mod:"
    }
}
