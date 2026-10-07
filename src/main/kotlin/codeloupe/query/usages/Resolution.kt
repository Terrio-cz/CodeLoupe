package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * What a reference may denote: [decls] it resolves to (empty = something outside the index), [complete] when no
 * other declaration can be meant, and [further] — members it would reach by dispatch further up the receiver's
 * supertypes (overridden by [decls]). [byName] marks an incomplete answer that holds every indexed declaration the
 * name could reach because the scope it is looked up in is not fully known.
 */
internal data class Resolution(
    val decls: List<DeclRow>,
    val complete: Boolean,
    val further: List<DeclRow> = emptyList(),
    val byName: Boolean = false,
) {
    fun filter(keep: (DeclRow) -> Boolean) = copy(decls = decls.filter(keep), further = further.filter(keep))

    companion object {
        val NOTHING = Resolution(emptyList(), complete = true)

        fun byName(decls: List<DeclRow>) = Resolution(decls, complete = false, byName = true)
    }
}
