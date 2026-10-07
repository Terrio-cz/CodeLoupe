package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * The declarations a usage search is for, plus the members they override ([overridden]) and that override them
 * ([overriding]): a call that reaches one of those may dispatch to a target.
 */
internal data class Targets(val decls: Set<DeclRow>, val overridden: Set<DeclRow>, val overriding: Set<DeclRow>) {
    fun label(r: Resolution, kind: String): Label {
        if (kind == "named_arg") return if (decls.any { it.kind == "property" }) Label.CANDIDATE else Label.OTHER
        val hit = r.decls.any { it in decls }
        return when {
            hit && r.complete && r.decls.all { it in decls } -> Label.EXACT
            hit -> Label.CANDIDATE
            r.further.any { it in decls } || r.decls.any { it in overridden || it in overriding } -> Label.CANDIDATE
            else -> Label.OTHER
        }
    }

    companion object {
        fun of(decls: Collection<DeclRow>, overrides: Overrides): Targets =
            Targets(decls.toSet(), decls.flatMap(overrides::overridden).toSet(), decls.flatMap(overrides::overriding).toSet())
    }
}
