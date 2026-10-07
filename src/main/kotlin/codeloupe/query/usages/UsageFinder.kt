package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow
import codeloupe.query.View

/**
 * Finds and labels every reference to a set of declarations. Every reference with a target's name (or an import
 * alias of it) is returned — unsure ones as candidates, ones that resolve elsewhere as [Label.OTHER] — so the
 * result covers `rg -w` over code. One finder serves one request; its caches assume an unchanging view.
 */
class UsageFinder(view: View) {
    internal val context = IndexContext(view)
    internal val cache get() = context.cache
    private val implicit = ImplicitScope(context.cache, context.types, context.visibility, context.lookup)
    private val libraryNames = LibraryNames(context.cache)
    private val deciding = HashSet<String>()
    private val outsideIndex = HashMap<String, Boolean>()
    private val resolved = HashMap<Triple<String, Int, Int>, Resolution>()
    private val specs = TypeSpecs(context.cache, settled = { deciding.isEmpty() }) { resolve(it) }
    private val resolver = RefResolver(context.cache, context.types, context.lookup, implicit, Receivers(context.cache, context.types, implicit, specs))

    fun usages(targets: Collection<DeclRow>): List<Usage> {
        val set = Targets.of(targets, context)
        val names = targets.map { it.name }.toSet() + targets.filter { !it.local }.flatMap(context.visibility::aliases)
        return names.flatMap(cache::refsNamed).map { ref ->
            Usage(ref, set.label(resolve(ref), ref), cache.owner(cache.file(ref.path)?.decl(ref.declId)))
        }
    }

    /** What one reference may denote. */
    internal fun resolve(ref: RefRow): Resolution {
        val key = Triple(ref.path, ref.line, ref.col)
        resolved[key]?.let { return it }
        val r = promoted(ref, resolver.resolve(ref))
        if (deciding.isEmpty()) resolved[key] = r
        return r
    }

    /**
     * When a name is looked up past a scope that is not fully known, a single indexed declaration of it is taken as
     * meant — unless the name is one libraries declare too, or a reference elsewhere surely resolves outside the index.
     * A heuristic: the golden test measures it.
     */
    private fun promoted(ref: RefRow, r: Resolution): Resolution {
        if (r.complete || !r.byName || r.decls.size != 1 || libraryNames.contains(ref.name) || declaredOutside(ref.name)) return r
        return r.copy(complete = true)
    }

    private fun declaredOutside(name: String): Boolean {
        outsideIndex[name]?.let { return it }
        // While deciding, assume it is: a type spec that leads back to this name gets no promotion from itself.
        if (!deciding.add(name)) return true
        try {
            return cache.refsNamed(name).any { it.kind in CALLS && resolver.resolve(it).let { r -> r.complete && r.decls.isEmpty() } }
                .also { if (deciding.size == 1) outsideIndex[name] = it }
        } finally {
            deciding.remove(name)
        }
    }

    private companion object {
        val CALLS = setOf("call", "nav", "callable_ref")
    }
}
