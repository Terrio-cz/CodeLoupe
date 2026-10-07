package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.Format
import codeloupe.query.Members
import codeloupe.query.PathOrder
import codeloupe.query.Resolver
import codeloupe.query.View

/** `hierarchy`: a type's supertypes and subtypes, or the members a member overrides and is overridden by. */
object HierarchyQuery {
    private const val MAX_LINES = 60
    private const val MAX_CODE = 100
    private val FUN_INTERFACE = Regex("""(^|\s)fun interface\s""")

    fun run(view: View, name: String?): String {
        val query = name.orEmpty()
        val found = Resolver.resolve(view, query)
        if (found.isEmpty()) return "no declaration \"$query\"" + Members.suggest(view, query)
        val targets = found.filter { it.kind != "constructor" }.ifEmpty { found }
        if (targets.size > 1) return TargetLines.ambiguity(query, targets) ?: "${targets.size} overloads of \"$query\" — give the parameters: member(ParamType)"
        val target = targets.single()
        val context = IndexContext(view)
        val lines = arrayListOf(Format.head(target))
        if (target.kind in Kinds.CLASSIFIERS) type(target, context.types, lines) else member(target, context.overrides, lines)
        if (target.kind == "interface" && FUN_INTERFACE.containsMatchIn(target.sig)) lambdas(view, target, lines)
        return lines.take(MAX_LINES).joinToString("\n") + Format.more(lines.size, MAX_LINES)
    }

    private fun type(type: DeclRow, types: Types, lines: MutableList<String>) {
        val supertypes = ArrayList<String>()
        fun up(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for ((name, resolved) in types.direct(t)) {
                if (resolved.isEmpty()) supertypes += "  ".repeat(depth) + "$name  (not indexed)"
                for (s in resolved) {
                    supertypes += "  ".repeat(depth) + ShortSignature.located(s)
                    if (seen.add(s)) up(s, depth + 1, seen)
                }
            }
        }
        up(type, 1, hashSetOf(type))
        if (supertypes.isNotEmpty()) lines += listOf("supertypes:") + supertypes
        val subtypes = ArrayList<String>()
        fun down(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for (s in types.directSubtypes(t)) {
                subtypes += "  ".repeat(depth) + ShortSignature.located(s)
                if (seen.add(s)) down(s, depth + 1, seen)
            }
        }
        down(type, 1, hashSetOf(type))
        lines += if (subtypes.isEmpty()) listOf("subtypes: (none indexed)") else listOf("subtypes:") + subtypes
    }

    /** `Iface { … }`: a lambda converted to a `fun interface` implements it too. */
    private fun lambdas(view: View, type: DeclRow, lines: MutableList<String>) {
        val finder = UsageFinder(view)
        val conversions = finder.usages(listOf(type)).filter { it.ref.kind == "call" && it.label != Label.OTHER }
        if (conversions.isEmpty()) return
        lines += "lambda implementations:"
        conversions.sortedWith(compareBy<Usage, String>(PathOrder) { it.ref.path }.thenBy { it.ref.line }).forEach {
            lines += "  ${it.label.mark} ${it.ref.path}:${it.ref.line}  ${finder.cache.line(it.ref.path, it.ref.line).trim().take(MAX_CODE)}"
        }
    }

    private fun member(member: DeclRow, overrides: Overrides, lines: MutableList<String>) {
        val up = overrides.overridden(member)
        val down = overrides.overriding(member)
        if (up.isNotEmpty()) lines += listOf("overrides:") + up.map { "  " + ShortSignature.located(it) }
        lines += if (down.isEmpty()) listOf("overridden by: (none indexed)") else listOf("overridden by:") + down.map { "  " + ShortSignature.located(it) }
    }
}
