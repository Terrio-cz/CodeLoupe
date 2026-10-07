package codeloupe.query.usages

import codeloupe.query.DeclRow

/** A type and all its indexed supertypes, nearest first; [names] adds supertypes outside the index (library types). */
internal data class TypeClosure(val levels: List<List<DeclRow>>, val names: Set<String>) {
    val types: List<DeclRow> get() = levels.flatten()

    companion object {
        val EMPTY = TypeClosure(emptyList(), emptySet())
    }
}
