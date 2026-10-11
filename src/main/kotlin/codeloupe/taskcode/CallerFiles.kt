package codeloupe.taskcode

import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder

/**
 * The other files that surely reference a file's top-level declarations, with the number of references (a floor: only exact hits count).
 * Types first and the longest first, at most [MAX_TARGETS] declarations, so a file with many small ones stays affordable.
 */
internal object CallerFiles {
    private val TYPES = setOf("class", "interface", "object", "enum", "annotation")
    private const val MAX_TARGETS = 4

    /** The public top-level declarations of [path] that are followed. */
    fun targets(view: View, path: String): List<DeclRow> =
        view.decls("f.path = :path AND d.local = 0 AND d.container = ''", mapOf("path" to path), "ORDER BY start_line")
            .filterNot { "private" in it.modifiers.split(' ') }
            .sortedWith(compareBy<DeclRow> { it.kind !in TYPES }.thenByDescending { it.endLine - it.startLine })
            .take(MAX_TARGETS)

    /** File -> number of exact references to [targets] from other files than [path]. */
    fun of(finder: UsageFinder, targets: List<DeclRow>, path: String): Map<String, Int> =
        finder.usages(targets).filter { it.label == Label.EXACT && it.ref.path != path }.groupingBy { it.ref.path }.eachCount()
}
