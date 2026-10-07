package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * A type and all its indexed supertypes, nearest first; [names] holds every simple name in it, [external] the names of
 * supertypes outside the index (library types), whose members nobody knows.
 */
internal data class TypeClosure(val levels: List<List<DeclRow>>, val names: Set<String>, val external: Set<String>)
