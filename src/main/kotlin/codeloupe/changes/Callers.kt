package codeloupe.changes

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.RefRow
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder

/**
 * Who uses a changed declaration, and which tests: a count and the first [TOP] users by name. Candidates (references
 * that may mean it) are counted; they are named only when there are few and no exact one, since a common name such
 * as `id` has hundreds. `usages` lists them all with code lines.
 */
internal class Callers(private val finder: UsageFinder, private val maxRefs: Int = MAX_REFS, private val budget: Int = BUDGET) {
    private var spent = 0

    /** The declaration as it is now: its usages, resolved exact or candidate. */
    fun of(decl: DeclRow): List<String> {
        tooCommon(decl)?.let { return listOf(it) }
        return lines(sites(decl), "callers")
    }

    /**
     * A changed signature: its usages, and calls that still fit the old parameters but now resolve to another overload
     * of the same name — the ones the change may have silently redirected.
     */
    fun ofChangedSignature(decl: DeclRow, old: DeclRow): List<String> {
        tooCommon(decl)?.let { return listOf(it) }
        val redirected = finder.cache.refsNamed(decl.name).filter { ref ->
            val args = ref.args ?: return@filter false
            ref.kind == "call" && finder.context.arguments.fits(old, args) &&
                finder.resolve(ref).decls.any { it.fqn == decl.fqn && it.kind == decl.kind && !(it.id == decl.id && it.src == decl.src) }
        }
        val redirectedLine = line("may be redirected (fit the old signature, now resolve to another overload)", redirected.map { site(it) }, Site::user)
        return lines(sites(decl), "callers") + listOfNotNull(redirectedLine)
    }

    /** A removed declaration: references with its name that do not surely resolve to another one may still mean it ("by name" says how sure). */
    fun ofRemoved(decl: DeclRow): List<String> {
        tooCommon(decl)?.let { return listOf(it) }
        return lines(removedSites(decl).map { site(it) }, "still referenced by name")
    }

    /** The references with the name of the removed [decl] that do not surely resolve to another declaration. */
    fun removedSites(decl: DeclRow): List<RefRow> {
        val kinds = KINDS[decl.kind] ?: return emptyList()
        return finder.cache.refsNamed(decl.name)
            .filter { it.kind in kinds }
            .filter { ref -> finder.resolve(ref).let { it.decls.isEmpty() || !it.complete } }
    }

    private fun sites(decl: DeclRow): List<Site> =
        finder.usages(listOf(decl)).filter { it.label != Label.OTHER && !sameDecl(it.owner, decl) }.map { Site(it.ref, it.owner, it.label == Label.CANDIDATE) }

    private fun site(ref: RefRow) = Site(ref, finder.cache.owner(finder.cache.file(ref.path)?.decl(ref.declId)), candidate = false)

    /**
     * Resolving every reference of a name like `id` or `get` costs seconds and says little, and one call resolves at most
     * [budget] references in all: past either, a declaration gets a note instead (usages pages through them).
     */
    private fun tooCommon(decl: DeclRow): String? {
        val refs = finder.cache.view.refCount(decl.name, maxRefs + 1)
        if (refs > maxRefs) return "callers: over $maxRefs references named ${decl.name}, too common to resolve here (usages lists them)"
        if (spent + refs > budget) return "callers: not resolved, this call's budget of $budget references is spent (usages lists them)"
        spent += refs
        return null
    }

    private fun lines(sites: List<Site>, what: String): List<String> {
        val (tests, code) = sites.partition { ModulePath.of(it.ref.path).sourceSet.contains("test", ignoreCase = true) }
        return listOfNotNull(line(what, code, Site::user), line("tests", tests, Site::testClass))
    }

    private fun line(what: String, sites: List<Site>, name: (Site) -> String): String? {
        if (sites.isEmpty()) return null
        val (candidates, exact) = sites.partition { it.candidate }
        val count = if (candidates.isEmpty()) "${exact.size}" else "${exact.size} + ${candidates.size} candidate"
        val named = when {
            exact.isNotEmpty() -> top(exact, name)
            candidates.size <= TOP -> top(candidates, name, mark = "?")
            else -> return "$what $count"
        }
        return "$what $count: $named"
    }

    /** Distinct users, most uses first. */
    private fun top(sites: List<Site>, name: (Site) -> String, mark: String = ""): String {
        val byUser = sites.groupBy(name).entries.sortedByDescending { it.value.size }
        return byUser.take(TOP).joinToString(", ") { (user, uses) -> mark + if (uses.size > 1) "$user ×${uses.size}" else user } +
            if (byUser.size > TOP) ", … +${byUser.size - TOP}" else ""
    }

    private fun sameDecl(owner: DeclRow?, decl: DeclRow) = owner != null && owner.id == decl.id && owner.src == decl.src

    private class Site(val ref: RefRow, val owner: DeclRow?, val candidate: Boolean) {
        private val file get() = ref.path.substringAfterLast('/')

        /** `Type.member`, `function (File.kt)` for a top-level one, `File.kt:line` outside any declaration. */
        fun user(): String = when {
            owner == null -> "$file:${ref.line}"
            owner.container.isEmpty() -> "${owner.name} ($file)"
            else -> "${owner.container}.${owner.name}"
        }

        /** Test names are sentences; their class says enough. */
        fun testClass(): String = owner?.let { it.container.substringBefore('.').ifEmpty { it.name } } ?: file
    }

    private companion object {
        const val TOP = 5
        const val MAX_REFS = 2_000
        const val BUDGET = 20_000
        private val TYPE_REFS = setOf("type", "call", "nav", "name", "callable_ref")
        val KINDS = mapOf(
            "fun" to setOf("call", "callable_ref"),
            "property" to setOf("nav", "name", "callable_ref"),
            "constructor" to setOf("call"),
            "enum_entry" to setOf("nav", "name"),
        ) + listOf("class", "interface", "object", "enum", "annotation", "typealias").associateWith { TYPE_REFS }
    }
}
