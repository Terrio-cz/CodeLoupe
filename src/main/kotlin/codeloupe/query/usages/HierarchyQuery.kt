package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.Format
import codeloupe.query.Members
import codeloupe.query.Resolver
import codeloupe.query.View

/** `hierarchy`: a type's supertypes and subtypes, or the members a member overrides and is overridden by. */
object HierarchyQuery {
    private const val MAX_LINES = 60

    fun run(view: View, name: String?): String {
        val query = name.orEmpty()
        val targets = Resolver.resolve(view, query)
        if (targets.isEmpty()) return "no declaration \"$query\"" + Members.suggest(view, query)
        if (targets.size > 1) return "${targets.size} declarations match \"$query\" — qualify it:\n" + targets.take(20).joinToString("\n", transform = Format::head)
        val target = targets.single()
        val finder = UsageFinder(view)
        val lines = ArrayList<String>()
        lines += Format.head(target)
        if (target.kind in Kinds.CLASSIFIERS) type(target, finder.types, lines) else member(target, finder.overrides, lines)
        return lines.take(MAX_LINES).joinToString("\n") + Format.more(lines.size, MAX_LINES)
    }

    private fun type(type: DeclRow, types: Types, lines: MutableList<String>) {
        lines += "supertypes:"
        val before = lines.size
        fun up(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for ((name, resolved) in types.direct(t)) {
                if (resolved.isEmpty()) lines += "  ".repeat(depth) + "$name  (not indexed)"
                for (s in resolved) {
                    lines += "  ".repeat(depth) + Format.head(s)
                    if (seen.add(s)) up(s, depth + 1, seen)
                }
            }
        }
        up(type, 1, hashSetOf(type))
        if (lines.size == before) lines.removeLast()
        lines += "subtypes:"
        val mark = lines.size
        fun down(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for (s in types.directSubtypes(t)) {
                lines += "  ".repeat(depth) + Format.head(s)
                if (seen.add(s)) down(s, depth + 1, seen)
            }
        }
        down(type, 1, hashSetOf(type))
        if (lines.size == mark) lines += "  (none indexed)"
    }

    private fun member(member: DeclRow, overrides: Overrides, lines: MutableList<String>) {
        val up = overrides.overridden(member)
        val down = overrides.overriding(member)
        if (up.isNotEmpty()) lines += "overrides:"
        up.forEach { lines += "  " + Format.head(it) }
        lines += if (down.isEmpty()) "overridden by: (none indexed)" else "overridden by:"
        down.forEach { lines += "  " + Format.head(it) }
    }
}
