package codeloupe.query.usages

import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow

/** Type names -> indexed type declarations, and the supertype / subtype relations between them. */
internal class Types(private val cache: IndexCache, private val visibility: Visibility) {
    private val closures = HashMap<DeclRow, TypeClosure>()
    private val inProgress = HashSet<DeclRow>()
    private val bySupertype: Map<String, List<DeclRow>> by lazy {
        val map = HashMap<String, MutableList<DeclRow>>()
        for (d in cache.view.decls("d.supertypes <> ''")) d.supertypes.split(' ').filter { it.isNotEmpty() }.forEach { map.getOrPut(it) { ArrayList() } += d }
        map
    }

    /**
     * `Foo`, `Foo<Bar>?`, `Outer.Inner`, `pkg.Foo` as seen from [at] in [file]; empty for library and function types.
     * Type aliases resolve to what they stand for unless [keepAliases].
     */
    fun resolve(text: String, file: FileScope, at: DeclRow?, keepAliases: Boolean = false): List<DeclRow> {
        val outer = DeclMatch.baseType(text)
        if ("->" in outer || outer.startsWith("(")) return emptyList()
        // `in T`, `@Ann Foo`, `suspend Foo` -> the last word.
        val base = outer.substringAfterLast(' ')
        if (base.isEmpty()) return emptyList()
        val parts = base.split('.')
        if (parts.size > 1) {
            cache.named(parts.last()).filter { it.fqn == base && it.kind in Kinds.TYPES }.let { if (it.isNotEmpty()) return it }
        }
        var current = simple(parts[0], file, at)
        for (part in parts.drop(1)) current = current.flatMap { t -> cache.children(t).filter { it.name == part && it.kind in Kinds.CLASSIFIERS } }
        return if (keepAliases) current else current.flatMap(::unalias)
    }

    /** [type] and its supertypes; an enum entry's supertype is its enum. */
    fun closure(type: DeclRow): TypeClosure {
        closures[type]?.let { return it }
        inProgress += type
        try {
            val seen = hashSetOf(type)
            val levels = ArrayList<List<DeclRow>>()
            val external = HashSet<String>()
            var level = listOf(type)
            while (level.isNotEmpty()) {
                levels += level
                val next = ArrayList<DeclRow>()
                for (t in level) {
                    for ((name, resolved) in direct(t)) {
                        if (resolved.isEmpty()) external += name
                        resolved.filter(seen::add).forEach(next::add)
                    }
                }
                level = next
            }
            return TypeClosure(levels, external + levels.flatten().map { it.name }, external).also { closures[type] = it }
        } finally {
            inProgress -= type
        }
    }

    /** Supertype name -> its declarations (empty outside the index). */
    fun direct(type: DeclRow): List<Pair<String, List<DeclRow>>> {
        val file = cache.file(type.path) ?: return emptyList()
        val listed = type.supertypes.split(' ').filter { it.isNotEmpty() }.map { it to resolve(it, file, cache.parent(type) ?: type) }
        val enum = if (type.kind == "enum_entry") listOfNotNull(cache.parent(type)).map { it.name to listOf(it) } else emptyList()
        return listed + enum
    }

    /** Indexed types that name [type] as a direct supertype, and the entries of an enum. */
    fun directSubtypes(type: DeclRow): List<DeclRow> =
        bySupertype[type.name].orEmpty().filter { d -> direct(d).any { (_, resolved) -> type in resolved } } +
            cache.children(type).filter { it.kind == "enum_entry" }

    fun allSubtypes(type: DeclRow): List<DeclRow> {
        val seen = LinkedHashSet<DeclRow>()
        val queue = ArrayDeque(directSubtypes(type))
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (seen.add(t)) queue.addAll(directSubtypes(t))
        }
        return seen.toList()
    }

    // Enclosing classes, the types nested in them and in their supertypes, then what the file imports.
    private fun simple(name: String, file: FileScope, at: DeclRow?): List<DeclRow> {
        for (outer in file.chain(at)) {
            if (outer.kind !in Kinds.CLASSIFIERS) continue
            if (outer.name == name) return listOf(outer)
            val holders = if (outer in inProgress) listOf(outer) else closure(outer).levels.flatten()
            holders.flatMap { h -> cache.children(h).filter { it.name == name && it.kind in Kinds.CLASSIFIERS } }.let { if (it.isNotEmpty()) return it }
        }
        return visibility.topLevel(name, file) { it.kind in Kinds.TYPES }
    }

    // One level is enough for the aliases code uses (`typealias Ids = List<Id>` resolves to nothing indexed anyway).
    private fun unalias(d: DeclRow): List<DeclRow> {
        if (d.kind != "typealias") return listOf(d)
        val file = cache.file(d.path) ?: return emptyList()
        return d.returns?.let { simple(DeclMatch.baseType(it).substringAfterLast('.'), file, null) }?.filter { it.kind != "typealias" }.orEmpty()
    }
}
