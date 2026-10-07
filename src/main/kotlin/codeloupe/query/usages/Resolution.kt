package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * What a reference may denote: [decls] it resolves to (empty = something outside the index), [complete] when no
 * other indexed declaration can be meant, and [further] — members it would reach by dispatch further up the
 * receiver's supertypes (overridden by [decls]).
 */
internal data class Resolution(val decls: List<DeclRow>, val complete: Boolean, val further: List<DeclRow> = emptyList()) {
    fun filter(keep: (DeclRow) -> Boolean) = copy(decls = decls.filter(keep), further = further.filter(keep))

    companion object {
        val NOTHING = Resolution(emptyList(), complete = true)
    }
}
