package codeloupe.query.usages

import codeloupe.query.CommonDir
import codeloupe.query.DeclRow
import codeloupe.query.Format
import codeloupe.query.Members
import codeloupe.query.Resolver
import codeloupe.query.SigText
import codeloupe.query.View

/** `hierarchy`: a type's supertypes and subtypes, or the members a member overrides and is overridden by. */
object HierarchyQuery {
    private const val MAX_LINES = 60
    private const val MAX_CODE = 100
    private val FUN_INTERFACE = Regex("""(^|\s)fun interface\s""")

    fun run(view: View, name: String?, deep: Boolean = false): String {
        val query = name.orEmpty()
        val found = Resolver.resolve(view, query)
        if (found.isEmpty()) return "no declaration \"$query\"" + Members.suggest(view, query)
        val targets = found.filter { it.kind != "constructor" }.ifEmpty { found }
        if (targets.size > 1) return TargetLines.ambiguity(query, targets) ?: "${targets.size} overloads of \"$query\" — give the parameters: member(ParamType)"
        val target = targets.single()
        val finder = UsageFinder(view)
        val isType = target.kind in Kinds.CLASSIFIERS
        val lines = arrayListOf("${target.path}:${Format.range(target)}  ${if (isType) typeHead(target) else ShortSignature.of(target)}")
        val dir = target.path.substringBeforeLast('/', "")
        if (isType) type(target, finder.context.types, deep, dir, lines) else member(target, finder.context.overrides, dir, lines)
        if (target.kind == "interface" && FUN_INTERFACE.containsMatchIn(target.sig)) lambdas(finder, target, lines)
        return lines.take(MAX_LINES).joinToString("\n") + Format.more(lines.size, MAX_LINES)
    }

    /** A line of a list: a declaration at a nesting depth, or a supertype name the index does not hold. */
    private class Entry(val depth: Int, val decl: DeclRow?, val unresolved: String = "")

    /** [deep]: the supertypes of the supertypes too; by default only the ones the type names itself. */
    private fun type(type: DeclRow, types: Types, deep: Boolean, dir: String, lines: MutableList<String>) {
        val supertypes = ArrayList<Entry>()
        fun up(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for ((name, resolved) in types.direct(t)) {
                if (resolved.isEmpty()) supertypes += Entry(depth, null, name)
                for (s in resolved) {
                    supertypes += Entry(depth, s)
                    if (deep && seen.add(s)) up(s, depth + 1, seen)
                }
            }
        }
        up(type, 1, hashSetOf(type))
        lines += section("supertypes", supertypes, dir)
        val subtypes = ArrayList<Entry>()
        fun down(t: DeclRow, depth: Int, seen: MutableSet<DeclRow>) {
            for (s in types.directSubtypes(t)) {
                subtypes += Entry(depth, s)
                if (seen.add(s)) down(s, depth + 1, seen)
            }
        }
        down(type, 1, hashSetOf(type))
        lines += if (subtypes.isEmpty()) listOf("subtypes: (none indexed)") else section("subtypes", subtypes, dir)
    }

    /**
     * A titled list. A file in [headDir], the directory of the first line, is written `./name`; the directory the other
     * paths share is named in the title instead of on every line.
     */
    private fun section(title: String, entries: List<Entry>, headDir: String): List<String> {
        if (entries.isEmpty()) return emptyList()
        fun beside(d: DeclRow) = headDir.isNotEmpty() && d.path.substringBeforeLast('/', "") == headDir
        val dir = CommonDir.of(entries.mapNotNull { it.decl }.filterNot(::beside).map { it.path })
        return listOf(CommonDir.heading(title, dir)) + entries.map {
            "  ".repeat(it.depth) + (it.decl?.let { d -> "${if (beside(d)) "./" + d.path.substringAfterLast('/') else d.path.removePrefix(dir)}:${d.declLine}  ${ShortSignature.of(d)}" } ?: "${it.unresolved}  (not indexed)")
        }
    }

    /** `[Container] modifiers kind Name<T>`: a type's constructor and supertypes are not its name; the supertypes are listed below. */
    private fun typeHead(d: DeclRow): String {
        val sig = SigText.plain(d.sig)
        val at = sig.indexOf(d.name)
        if (at < 0) return ShortSignature.of(d)
        var end = at + d.name.length
        if (end < sig.length && sig[end] == '<') {
            var open = 0
            while (end < sig.length) {
                if (sig[end] == '<') open++ else if (sig[end] == '>' && --open == 0) { end++; break }
                end++
            }
        }
        return (if (d.container.isNotEmpty()) "[${d.container}] " else "") + sig.substring(0, end)
    }

    /**
     * `Iface { … }`: a lambda converted to a `fun interface` implements it too. A lambda passed straight to a parameter
     * of that type is not listed — finding those means typing every call's parameters.
     */
    private fun lambdas(finder: UsageFinder, type: DeclRow, lines: MutableList<String>) {
        val conversions = HitLines.lines(finder.usages(listOf(type)).filter { it.ref.kind == "call" && it.label != Label.OTHER })
        if (conversions.isEmpty()) return
        lines += "lambda implementations (${type.name} { … }):"
        conversions.forEach { lines += "  ${it.label.mark} ${it.ref.path}:${it.ref.line}  ${finder.cache.line(it.ref.path, it.ref.line).trim().take(MAX_CODE)}" }
    }

    private fun member(member: DeclRow, overrides: Overrides, dir: String, lines: MutableList<String>) {
        val up = overrides.overridden(member)
        val down = overrides.overriding(member)
        lines += section("overrides", up.map { Entry(1, it) }, dir)
        lines += if (down.isEmpty()) listOf("overridden by: (none indexed)") else section("overridden by", down.map { Entry(1, it) }, dir)
    }
}
