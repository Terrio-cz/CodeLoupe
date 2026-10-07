package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow

/**
 * The declarations a usage search is for, plus the members they override ([overridden]), that override them
 * ([overriding]) and the classes of target constructors ([constructed]): a call that reaches one of those may
 * dispatch to, or construct through, a target. A named argument counts when passed to a call in [namedBy].
 */
internal class Targets(
    val decls: Set<DeclRow>,
    val overridden: Set<DeclRow>,
    val overriding: Set<DeclRow>,
    val constructed: Set<DeclRow>,
    val namedBy: Set<String>,
    private val arguments: Arguments,
) {
    fun label(r: Resolution, ref: RefRow): Label {
        val kind = ref.kind
        if (kind == "named_arg") return if (namedBy.isNotEmpty() && (ref.recv == null || ref.recv in namedBy)) Label.CANDIDATE else Label.OTHER
        val hit = r.decls.any { it in decls }
        return when {
            hit && r.complete && r.decls.all { it in decls } -> Label.EXACT
            hit -> Label.CANDIDATE
            r.further.any { it in decls } || r.decls.any { it in overridden || it in overriding } -> Label.CANDIDATE
            kind == "call" && r.decls.any { it in constructed } && constructorFits(ref.args) -> Label.CANDIDATE
            else -> Label.OTHER
        }
    }

    // A call of the class reaches a target constructor only when its arguments fit that constructor.
    private fun constructorFits(args: Int?) =
        args == null || args < 0 || decls.any { it.kind == "constructor" && arguments.fits(it, args) }

    companion object {
        fun of(decls: Collection<DeclRow>, context: IndexContext): Targets = Targets(
            decls.toSet(),
            decls.flatMap(context.overrides::overridden).toSet(),
            decls.flatMap(context.overrides::overriding).toSet(),
            decls.filter { it.kind == "constructor" }.mapNotNull(context.cache::parent).toSet(),
            namedArgumentCallees(decls, context),
            context.arguments,
        )

        // A property declared in a constructor is passed by name to that constructor (by its name, an import alias,
        // `this(…)` or `super(…)`) or to a data class's `copy`.
        private fun namedArgumentCallees(decls: Collection<DeclRow>, context: IndexContext): Set<String> {
            val owners = decls.filter { it.kind == "property" }.mapNotNull(context.cache::parent)
            if (owners.isEmpty()) return emptySet()
            return owners.flatMap { listOf(it.name) + context.visibility.aliases(it) }.toSet() + setOf("copy", "this", "super")
        }

        /**
         * Declarations one usage search may cover together: overloads share a name and container, a class goes with its
         * constructors. Anything else is ambiguous and must be qualified.
         */
        fun oneSymbol(decls: List<DeclRow>): Boolean =
            decls.map { if (it.kind == "constructor") it.fqn.substringBeforeLast('.') else it.fqn }.distinct().size == 1
    }
}
