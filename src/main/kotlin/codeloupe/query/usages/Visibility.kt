package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * Top-level declarations a file sees under a simple name, in Kotlin's order: explicit imports (aliases
 * included), the file's own package, star imports. An explicit import of a library name hides the rest. Only
 * declarations [accept] keeps count, so an unreachable one never hides the next level.
 */
internal class Visibility(private val cache: IndexCache) {
    fun topLevel(name: String, file: FileScope, accept: (DeclRow) -> Boolean = { true }): List<DeclRow> {
        val reachable = { d: DeclRow -> !d.local && Kinds.accessible(d, file.path) && accept(d) }
        val explicit = file.imports.filter { !it.star && (it.alias ?: it.fqn.substringAfterLast('.')) == name }
        if (explicit.isNotEmpty()) {
            return explicit.flatMap { imp -> cache.named(imp.fqn.substringAfterLast('.')).filter { it.fqn == imp.fqn && reachable(it) } }
        }
        val named = cache.named(name).filter(reachable)
        named.filter { it.container.isEmpty() && Kinds.packageOf(it) == file.packageName }.let { if (it.isNotEmpty()) return it }
        val stars = file.imports.filter { it.star }.map { it.fqn + "." + name }.toSet()
        return named.filter { it.fqn in stars }
    }

    /** Names under which a file can refer to [d]: its own and every import alias of it. */
    fun aliases(d: DeclRow): Set<String> =
        cache.view.imports("i.fqn = :fqn AND i.alias IS NOT NULL", mapOf("fqn" to d.fqn)).mapNotNull { it.alias }.toSet()
}
