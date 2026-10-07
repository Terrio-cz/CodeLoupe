package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.PathOrder
import codeloupe.query.RefRow

/** One level of a call tree: the callers of declarations (from their usages) or their callees (from their references). */
internal class CallTree(private val finder: UsageFinder, private val direction: Direction) {
    enum class Direction { CALLERS, CALLEES }

    /** A caller or callee; [decl] is null for code outside any declaration. [lines] are where the call is. */
    data class Node(val decl: DeclRow?, val path: String, val label: Label, val lines: List<Int>) {
        fun text(): String {
            val where = if (decl == null) "$path:${lines.joinToString(",")}  (file level)" else ShortSignature.located(decl)
            return "${label.mark} $where" + if (decl != null) "  @${lines.joinToString(",")}" else ""
        }
    }

    /** Calls the tree could not attribute to an indexed declaration (library calls, unknown receivers). */
    var unresolved = 0
        private set

    fun children(of: List<DeclRow>): List<Node> = when (direction) {
        Direction.CALLERS -> callers(of)
        Direction.CALLEES -> callees(of)
    }

    private fun callers(of: List<DeclRow>): List<Node> =
        finder.usages(of).filter { it.label != Label.OTHER && it.ref.kind in CALL_KINDS }
            .groupBy { it.owner to it.ref.path }
            .map { (key, hits) -> Node(key.first, key.second, hits.minOf { it.label }, hits.map { it.ref.line }.distinct().sorted()) }
            .sortedWith(ORDER)

    private fun callees(of: List<DeclRow>): List<Node> {
        val hits = ArrayList<Pair<DeclRow, Pair<Label, Int>>>()
        for (d in of) {
            for (ref in refsInside(d)) {
                val r = finder.resolve(ref)
                if (r.decls.isEmpty() || r.decls.size > MAX_GUESSES) {
                    unresolved++
                    continue
                }
                val label = if (r.complete && r.decls.size == 1) Label.EXACT else Label.CANDIDATE
                r.decls.forEach { hits += it to (label to ref.line) }
            }
        }
        return hits.groupBy({ it.first }, { it.second })
            .map { (decl, uses) -> Node(decl, decl.path, uses.minOf { it.first }, uses.map { it.second }.distinct().sorted()) }
            .sortedWith(ORDER)
    }

    private fun refsInside(d: DeclRow): List<RefRow> {
        val file = finder.cache.file(d.path) ?: return emptyList()
        return finder.cache.view.refs(
            "f.path = :path AND r.line BETWEEN :start AND :end AND r.kind IN ('call', 'callable_ref')",
            mapOf("path" to d.path, "start" to d.declLine, "end" to d.endLine),
        ).filter { ref -> file.chain(file.decl(ref.declId)).any { it.id == d.id } }
    }

    private companion object {
        /** Kinds of reference that run code: calls, `::refs`, and reads of a property or object. */
        val CALL_KINDS = setOf("call", "callable_ref", "nav", "name")

        /** A callee with more possible declarations than this is reported as unresolved, not as a list of guesses. */
        const val MAX_GUESSES = 3

        val ORDER: Comparator<Node> = compareBy<Node> { it.label }.thenComparator { a, b -> PathOrder.compare(a.path, b.path) }.thenBy { it.lines.first() }
    }
}
