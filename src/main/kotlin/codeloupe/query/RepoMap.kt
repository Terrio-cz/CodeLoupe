package codeloupe.query

import codeloupe.query.usages.ShortSignature
import kotlin.math.sqrt

/**
 * `outline` without a target: a map of the repository's types, files ranked by how much the rest of the code refers to
 * them (PageRank over the name references of the index), cut to a token budget. With a focus — files or symbols — the
 * walk is personalized: their neighbourhood ranks first, references counted in both directions.
 */
object RepoMap {
    data class Args(val focus: List<String> = emptyList(), val budget: Int = DEFAULT_BUDGET, val test: Boolean? = null)

    const val DEFAULT_BUDGET = 1500
    private const val CHARS_PER_TOKEN = 3.2
    private const val TYPES_PER_FILE = 10
    private const val REVERSE_WEIGHT = 0.5
    private const val TYPE_KINDS = "('class','interface','object','enum','annotation')"

    fun run(view: View, args: Args): String {
        val all = view.decls("d.local = 0 AND d.kind IN $TYPE_KINDS")
        if (all.isEmpty()) return "no types in the index"
        val focus = Focus.resolve(view, args.focus)
        val tests = all.filter { "test" in it.sourceSet.lowercase() }.map { it.path }.toSet()
        val withTests = args.test ?: focus.paths.any { it in tests }
        val types = all.filter { withTests || it.path !in tests }
        val nodes = types.map { it.path }.toSet()
        val ranks = FileRank.rank(nodes, edges(view, types, nodes, focus.paths.isNotEmpty()), focus.paths)
        val byFile = types.filter { it.container.isEmpty() }.groupBy { it.path }
        // The focus leads: what the agent is working on first, then what the walk found around it.
        val ordered = ranks.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.key in focus.paths }.thenByDescending { it.value }.thenBy(PathOrder) { it.key })
            .map { it.key }.filter { it in byFile }

        val limit = (args.budget.coerceIn(100, 8000) * CHARS_PER_TOKEN).toInt()
        val out = StringBuilder("map of ${nodes.size} files, ${all.size} types" + focusNote(focus))
        var shown = 0
        for (path in ordered) {
            val decls = byFile.getValue(path).sortedBy { it.startLine }
            val block = path + decls.take(TYPES_PER_FILE).joinToString("") { "\n  " + ShortSignature.of(it) } +
                if (decls.size > TYPES_PER_FILE) "\n  … +${decls.size - TYPES_PER_FILE} more types" else ""
            if (shown > 0 && out.length + block.length + 1 > limit) break
            out.append('\n').append(block)
            shown++
        }
        if (shown < ordered.size) out.append("\n… +${ordered.size - shown} more files (raise budget, or give focus)")
        return out.toString()
    }

    /** Weight of a file referring to a type name: the square root of the count, shared between same-named types. */
    private fun edges(view: View, types: List<DeclRow>, nodes: Set<String>, both: Boolean): Map<String, Map<String, Double>> {
        val filesOf = types.groupBy({ it.name }, { it.path }).mapValues { it.value.toSet() }
        val edges = HashMap<String, MutableMap<String, Double>>()
        for (ref in view.typeRefCounts()) {
            if (ref.path !in nodes) continue
            val targets = filesOf[ref.name].orEmpty().filter { it != ref.path }
            if (targets.isEmpty()) continue
            val weight = sqrt(ref.count.toDouble()) / targets.size
            for (target in targets) {
                edges.getOrPut(ref.path) { HashMap() }.merge(target, weight, Double::plus)
                if (both) edges.getOrPut(target) { HashMap() }.merge(ref.path, weight * REVERSE_WEIGHT, Double::plus)
            }
        }
        return edges
    }

    private fun focusNote(focus: Focus): String = buildString {
        if (focus.paths.isNotEmpty()) append(", focus ${focus.paths.sorted().joinToString(", ")}")
        if (focus.missing.isNotEmpty()) append(", not found: ${focus.missing.joinToString(", ")}")
    }
}
