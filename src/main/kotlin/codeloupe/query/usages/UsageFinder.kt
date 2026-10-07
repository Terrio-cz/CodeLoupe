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
    internal val cache = IndexCache(view)
    private val visibility = Visibility(cache)
    internal val types = Types(cache, visibility)
    private val lookup = MemberLookup(cache, types, visibility)
    private val implicit = ImplicitScope(cache, types, visibility, lookup)
    private val specs = TypeSpecs(cache) { resolve(it) }
    private val outsideIndex = HashMap<String, Boolean>()
    private val resolver: RefResolver = RefResolver(cache, types, lookup, implicit, Receivers(cache, types, implicit, specs))
    internal val overrides = Overrides(cache, types)

    fun usages(targets: Collection<DeclRow>): List<Usage> {
        val set = Targets.of(targets, overrides)
        val names = targets.map { it.name }.toSet() + targets.filter { !it.local }.flatMap(visibility::aliases)
        return names.flatMap { refsNamed(it) }.map { ref ->
            val file = cache.file(ref.path)
            Usage(ref, set.label(resolve(ref), ref.kind), cache.owner(file?.decl(ref.declId)))
        }
    }

    /** What one reference may denote. */
    internal fun resolve(ref: RefRow): Resolution = promoted(ref, resolver.resolve(ref))

    /**
     * On a receiver of unknown type, a name the index declares once is that declaration — unless it is a common
     * library name, or another reference shows that something outside the index declares it too.
     */
    private fun promoted(ref: RefRow, r: Resolution): Resolution {
        if (r.complete || r.decls.size != 1 || LibraryNames.contains(ref.name) || declaredOutside(ref.name)) return r
        return r.copy(complete = true)
    }

    private fun declaredOutside(name: String): Boolean {
        outsideIndex[name]?.let { return it }
        // While deciding, assume it is: a type spec that leads back to this name gets no promotion from itself.
        outsideIndex[name] = true
        val outside = refsNamed(name).any { it.kind in CALLS && resolver.resolve(it).let { r -> r.complete && r.decls.isEmpty() } }
        outsideIndex[name] = outside
        return outside
    }

    private fun refsNamed(name: String): List<RefRow> = cache.view.refs("r.name = :name", mapOf("name" to name))

    private companion object {
        val CALLS = setOf("call", "nav", "callable_ref")
    }
}
