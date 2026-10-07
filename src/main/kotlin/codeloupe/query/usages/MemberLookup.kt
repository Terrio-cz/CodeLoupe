package codeloupe.query.usages

import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow

/** Members and extensions a name can reach on a type. */
internal class MemberLookup(private val cache: IndexCache, private val types: Types, private val visibility: Visibility) {
    /** Instance members on [closure]: the nearest supertype level that declares one wins, the rest are reached by dispatch. */
    fun instance(closure: TypeClosure, name: String, accept: (DeclRow) -> Boolean): Resolution {
        val levels = closure.levels.map { level ->
            level.flatMap { t -> cache.children(t).filter { it.name == name && !it.local && it.kind !in Kinds.CLASSIFIERS && accept(it) } }
        }
        val nearest = levels.indexOfFirst { it.isNotEmpty() }
        if (nearest < 0) return Resolution(emptyList(), complete = true)
        return Resolution(levels[nearest], complete = true, further = levels.drop(nearest + 1).flatten())
    }

    /** `Type.name`: nested types and enum entries, members of an object, of the companion objects. */
    fun static(type: DeclRow, name: String, accept: (DeclRow) -> Boolean): Resolution {
        val children = cache.children(type)
        val nested = children.filter { it.name == name && it.kind in Kinds.CLASSIFIERS && accept(it) }
        val holders = (if (type.kind == "object" || type.kind == "companion") listOf(type) else emptyList()) + children.filter { it.kind == "companion" }
        val members = holders.map { instance(types.closure(it), name, accept) }
        return Resolution(nested + members.flatMap { it.decls }, complete = true, further = members.flatMap { it.further })
    }

    /** Visible extensions named [name] whose receiver is one of [receiverNames] (simple names), `Any` or a type parameter. */
    fun extensions(name: String, receiverNames: Set<String>, file: FileScope, accept: (DeclRow) -> Boolean): List<DeclRow> {
        val all = cache.named(name).filter { it.receiver != null && !it.local && accept(it) && fits(it.receiver, receiverNames) }
        if (all.isEmpty()) return all
        val visible = visibility.topLevel(name, file).toSet()
        return all.filter { it in visible || it.container.isNotEmpty() || it.path == file.path }
    }

    private fun fits(receiver: String, names: Set<String>): Boolean {
        val base = DeclMatch.baseType(receiver).substringAfterLast(' ').substringAfterLast('.')
        return base in names || base == "Any" || Kinds.isTypeParameter(base)
    }
}
