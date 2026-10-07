package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/**
 * Resolves the type specs the extractor records for locals and receivers (see [codeloupe.lang.RefFact]): plain type
 * text, `@line:col` — the declared type of what the reference there denotes — and `*spec`, an element of `spec`.
 */
internal class TypeSpecs(private val cache: IndexCache, private val resolveRef: (RefRow) -> Resolution) {
    /** Type text and the place its names are resolved from. */
    data class TypeText(val text: String, val file: FileScope, val at: DeclRow?)

    private val memo = HashMap<Triple<String, String, Long?>, TypeText?>()
    private val active = HashSet<Triple<String, String, Long?>>()

    fun text(spec: String, file: FileScope, at: DeclRow?): TypeText? {
        if (spec.isEmpty()) return null
        val key = Triple(file.path, spec, at?.id)
        memo[key]?.let { return it }
        if (key in memo || active.size >= MAX_DEPTH || !active.add(key)) return null
        try {
            return compute(spec, file, at).also { memo[key] = it }
        } finally {
            active.remove(key)
        }
    }

    /** The declared type of [d]: its return or property type, `= Type(...)` when undeclared, the class itself. */
    fun declaredType(d: DeclRow): TypeText? {
        val file = cache.file(d.path) ?: return null
        if (d.kind in Kinds.CLASSIFIERS) return TypeText(d.fqn, file, null)
        val text = d.returns ?: INITIALIZER.find(file.line(d.declLine))?.groupValues?.get(1) ?: return null
        return TypeText(text, file, cache.parent(d))
    }

    private fun compute(spec: String, file: FileScope, at: DeclRow?): TypeText? = when (spec[0]) {
        '*' -> text(spec.substring(1), file, at)?.let { inner ->
            ITERABLE.matchEntire(inner.text.trim())?.groupValues?.get(1)?.let { TypeText(it, inner.file, inner.at) }
        }
        '@' -> referenced(spec.substringBefore('|'), file) ?: spec.substringAfter('|', "").let { if (it.isEmpty()) null else text(it, file, at) }
        else -> TypeText(spec, file, at)
    }

    // Only a reference that surely resolves gives a type; a guess would spread into every use of the local.
    private fun referenced(position: String, file: FileScope): TypeText? {
        val (line, col) = position.substring(1).split(':').map(String::toInt)
        val resolution = cache.refAt(file.path, line, col)?.let(resolveRef)?.takeIf { it.complete } ?: return null
        return resolution.decls.mapNotNull(::declaredType).distinctBy { it.text }.singleOrNull()
    }

    private companion object {
        const val MAX_DEPTH = 6
        val ITERABLE = Regex("""(?:Mutable)?(?:List|Set|Collection|Iterable|Sequence|Array)<\s*([^,<>]+?)\s*>\??""")

        /** `val x = Type(...)`, `val x by lazy { Type(...) }`, `fun f() = Type(...)`. */
        val INITIALIZER = Regex("""(?:=|by\s+lazy\s*\{)\s*((?:[a-z_]\w*\.)*[A-Z][\w.]*)\s*(?:<[^()]*>)?\s*\(""")
    }
}
