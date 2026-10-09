package codeloupe.secrets.imports

import codeloupe.secrets.SecretMeta
import codeloupe.secrets.SecretScope

/**
 * Which variables of a scan would hide a secret the store already holds under the same name in a wider scope. A variable takes the scope
 * of the folder it was found in, and the narrowest scope wins when a process asks for its secrets, so an `API_KEY` in a repository
 * somebody else wrote would replace the user's global `API_KEY` for everything run inside that folder.
 */
class Shadowing(stored: Collection<SecretMeta>) {
    private val storedByName: Map<String, List<SecretScope>> =
        stored.mapNotNull { m -> runCatching { m.name to SecretScope.parse(m.scope) }.getOrNull() }.groupBy({ it.first }, { it.second })

    /** The wider scopes (as text, sorted) whose stored secret of this name [variable] would hide; empty when it hides nothing. */
    fun of(variable: FoundVariable): List<String> = of(variable.name, variable.scope)

    fun of(name: String, scope: SecretScope): List<String> =
        storedByName[name].orEmpty().filter { it.rank < scope.rank && applies(it, scope) }.map { it.toString() }.distinct().sorted()

    /** The occurrences among [found] that hide something. */
    fun hiding(found: Collection<FoundVariable>): List<FoundVariable> = found.filter { of(it).isNotEmpty() }

    private fun applies(wider: SecretScope, narrow: SecretScope): Boolean = when (wider.kind) {
        SecretScope.Kind.GLOBAL -> true
        SecretScope.Kind.WORKSPACE -> narrow.kind == SecretScope.Kind.REPOSITORY && narrow.id.orEmpty().startsWith(wider.id.orEmpty().trimEnd('/') + "/")
        SecretScope.Kind.REPOSITORY -> false
    }
}
