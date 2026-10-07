package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.Members
import codeloupe.query.Resolver
import codeloupe.query.View

/** `calls`: who calls a declaration (callers) or what it calls (callees), as a tree up to three levels deep. */
object CallsQuery {
    data class Args(val name: String?, val callees: Boolean = false, val depth: Int = 2, val limit: Int = 40)

    private const val MAX_DEPTH = 3
    private const val MAX_CHILDREN = 12

    fun run(view: View, args: Args): String {
        val name = args.name.orEmpty()
        val targets = Resolver.resolve(view, name)
        if (targets.isEmpty()) return "no declaration \"$name\"" + Members.suggest(view, name)
        TargetLines.ambiguity(name, targets)?.let { return it }
        val finder = UsageFinder(view)
        val tree = CallTree(finder, if (args.callees) CallTree.Direction.CALLEES else CallTree.Direction.CALLERS)
        val depth = args.depth.coerceIn(1, MAX_DEPTH)
        val out = Rendering(args.limit)
        out.line(TargetLines.header(if (args.callees) "callees" else "callers", targets))
        val seen = HashSet<DeclRow>(targets)
        // Below the first level only exact links are drawn; guesses about guesses are counted, not listed.
        fun expand(all: List<CallTree.Node>, level: Int) {
            val nodes = if (level == 1) all else all.filter { it.label == Label.EXACT }
            for ((i, node) in nodes.withIndex()) {
                if (i == MAX_CHILDREN) {
                    out.line("  ".repeat(level) + "… +${nodes.size - i} more")
                    break
                }
                val repeated = node.decl != null && !seen.add(node.decl)
                if (!out.line("  ".repeat(level) + node.text() + if (repeated) "  (above)" else "")) return
                if (!repeated && node.decl != null && level < depth) expand(tree.children(listOf(node.decl)), level + 1)
            }
            val guesses = all.size - nodes.size
            if (guesses > 0) out.line("  ".repeat(level) + "? $guesses candidate${if (guesses == 1) "" else "s"} not shown")
        }
        expand(tree.children(targets), 1)
        tree.unresolved.takeIf { it > 0 && args.callees }?.let { out.line("$it calls into libraries or unresolved code not shown") }
        return out.text()
    }

    /** Lines up to [limit] tree nodes, then one "… +N more" marker. */
    private class Rendering(private val limit: Int) {
        private val lines = ArrayList<String>()
        private var nodes = 0
        private var cut = false

        fun line(text: String): Boolean {
            if (cut) return false
            if (nodes++ > limit) {
                lines += "… more nodes (raise limit or lower depth)"
                cut = true
                return false
            }
            lines += text
            return true
        }

        fun text() = lines.joinToString("\n")
    }
}
