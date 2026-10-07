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
internal class Callers(private val finder: UsageFinder) {
    /** The declaration as it is now: its usages, resolved exact or candidate. */
    fun of(decl: DeclRow): List<String> {
        val usages = finder.usages(listOf(decl)).filter { it.label != Label.OTHER && !sameDecl(it.owner, decl) }
        return lines(usages.map { Site(it.ref, it.owner, it.label == Label.CANDIDATE) }, "callers")
    }

    /** A removed declaration: references with its name that do not surely resolve to another one may still mean it ("by name" says how sure). */
    fun ofRemoved(decl: DeclRow): List<String> {
        val kinds = KINDS[decl.kind] ?: return emptyList()
        val sites = finder.cache.refsNamed(decl.name)
            .filter { it.kind in kinds }
            .filter { ref -> finder.resolve(ref).let { it.decls.isEmpty() || !it.complete } }
            .map { Site(it, finder.cache.owner(finder.cache.file(it.path)?.decl(it.declId)), candidate = false) }
        return lines(sites, "still referenced by name")
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
        const val TOP = 10
        private val TYPE_REFS = setOf("type", "call", "nav", "name", "callable_ref")
        val KINDS = mapOf(
            "fun" to setOf("call", "callable_ref"),
            "property" to setOf("nav", "name", "callable_ref"),
            "constructor" to setOf("call"),
            "enum_entry" to setOf("nav", "name"),
        ) + listOf("class", "interface", "object", "enum", "annotation", "typealias").associateWith { TYPE_REFS }
    }
}
